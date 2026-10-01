package com.tribule.ledger.settlement;

import com.tribule.ledger.config.LedgerProperties;
import com.tribule.ledger.fx.FxRateService;
import com.tribule.ledger.fx.ResolvedRate;
import com.tribule.ledger.ledger.AccountRepository;
import com.tribule.ledger.ledger.ChartOfAccounts;
import com.tribule.ledger.ledger.CurrencyRepository;
import com.tribule.ledger.ledger.LedgerService;
import com.tribule.ledger.ledger.PostedTransaction;
import com.tribule.ledger.ledger.PostingCommand;
import com.tribule.ledger.ledger.TransactionKind;
import com.tribule.ledger.money.Conversion;
import com.tribule.ledger.money.CurrencyUnit;
import com.tribule.ledger.money.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Opens and closes FX authorizations.
 *
 * <p>Authorizing does two distinct things, and keeping them distinct is the whole
 * design:
 *
 * <ol>
 *   <li><b>It reserves real money.</b> The customer's funds move out of the
 *       spendable wallet into a hold account in the same currency. The two legs
 *       balance, nothing is created, and because the wallet is marked as not
 *       allowed to go negative, "insufficient funds" is enforced by the database
 *       rather than by a balance check that another request could race.
 *   <li><b>It records an obligation that is not yet a cash movement.</b> The buy
 *       side has not happened -- no buy currency has moved -- so booking it to
 *       the balance sheet would be a lie. It goes to contingent accounts instead,
 *       which are real double-entry accounts that balance per currency but sit
 *       off the balance sheet.
 * </ol>
 *
 * <p>Between this and settlement the house carries the rate. That is the exposure
 * the risk endpoints report.
 */
@Service
public class AuthorizationService {

    /** What a caller has to supply to get a quote held. */
    public record AuthorizeCommand(
            String reference,
            String customerId,
            String sellCurrency,
            String buyCurrency,
            long sellAmountMinor,
            Instant authorizedAt,
            Duration ttl) {
    }

    public record AuthorizationResult(
            FxAuthorization authorization,
            PostedTransaction holdTransaction,
            ResolvedRate quotedRate,
            Conversion quote) {
    }

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final AuthorizationRepository authorizations;
    private final AccountRepository accounts;
    private final CurrencyRepository currencies;
    private final LedgerService ledger;
    private final FxRateService fx;
    private final LedgerProperties properties;
    private final Counter authorized;
    private final Counter released;

    public AuthorizationService(AuthorizationRepository authorizations,
                               AccountRepository accounts,
                               CurrencyRepository currencies,
                               LedgerService ledger,
                               FxRateService fx,
                               LedgerProperties properties,
                               MeterRegistry registry) {
        this.authorizations = authorizations;
        this.accounts = accounts;
        this.currencies = currencies;
        this.ledger = ledger;
        this.fx = fx;
        this.properties = properties;
        this.authorized = Counter.builder("ledger.authorizations.opened").register(registry);
        this.released = Counter.builder("ledger.authorizations.closed").register(registry);
    }

    @Transactional
    public AuthorizationResult authorize(AuthorizeCommand command, UUID transactionId) {
        CurrencyUnit sell = currencies.require(command.sellCurrency());
        CurrencyUnit buy = currencies.require(command.buyCurrency());
        if (sell.code().equals(buy.code())) {
            throw new SettlementException("an authorization must convert between two different currencies");
        }

        Instant authorizedAt = command.authorizedAt() == null ? Instant.now() : command.authorizedAt();
        Duration ttl = command.ttl() == null ? properties.authorizationTtl() : command.ttl();

        // Price the quote at what we knew at the moment of authorization, not at
        // whatever has been published since -- otherwise a rate correction
        // published later would silently change what the customer was quoted.
        ResolvedRate rate = fx.resolve(sell.code(), buy.code(), authorizedAt, authorizedAt);
        Money sellAmount = Money.ofMinor(command.sellAmountMinor(), sell);
        Conversion quote = fx.convert(sellAmount, buy, rate);

        accounts.ensureCustomerWallet(command.customerId(), sell.code());
        accounts.ensureCustomerHold(command.customerId(), sell.code());
        // The buy wallet is created now rather than at settlement, so settlement
        // cannot fail on account creation after the money is already committed.
        accounts.ensureCustomerWallet(command.customerId(), buy.code());

        long quotedBuyMinor = quote.booked().minorUnits();
        if (quotedBuyMinor <= 0) {
            throw new SettlementException(
                    "%s converts to zero %s at the quoted rate; amount is below one minor unit"
                            .formatted(sellAmount, buy.code()));
        }

        PostedTransaction hold = ledger.post(PostingCommand.of(TransactionKind.AUTHORIZATION_HOLD, authorizedAt)
                .id(transactionId)
                .reference(command.reference())
                .description("hold for authorization " + command.reference())
                .fxRate(rate.primaryRateId())
                .correlationId(command.reference())
                // Real funds reserved: out of spendable, into held.
                .debit(ChartOfAccounts.wallet(command.customerId(), sell.code()),
                        command.sellAmountMinor(), "reserved for " + command.reference())
                .credit(ChartOfAccounts.hold(command.customerId(), sell.code()),
                        command.sellAmountMinor(), "held for " + command.reference())
                // Obligation on the buy side: recorded, but off the balance sheet.
                .debit(ChartOfAccounts.fxCommitmentContra(buy.code()),
                        quotedBuyMinor, "committed to deliver " + command.reference())
                .credit(ChartOfAccounts.fxCommitment(buy.code()),
                        quotedBuyMinor, "committed to deliver " + command.reference())
                .build());

        FxAuthorization stored = authorizations.insert(new FxAuthorization(
                UUID.randomUUID(),
                command.reference(),
                command.customerId(),
                sell.code(),
                buy.code(),
                command.sellAmountMinor(),
                quotedBuyMinor,
                rate.rate(),
                rate.primaryRateId(),
                AuthorizationStatus.PENDING,
                authorizedAt,
                authorizedAt.plus(ttl),
                null,
                hold.transaction().id(),
                null, null, null, null, null, 1));

        authorized.increment();
        log.debug("authorized {} {}->{} at {} ({})", command.reference(), sell.code(), buy.code(),
                rate.rate(), rate.resolution());
        return new AuthorizationResult(stored, hold, rate, quote);
    }

    /**
     * Cancels an open authorization.
     *
     * <p>The hold is undone by reversing the hold transaction rather than by
     * posting a hand-built opposite, so the released amount is guaranteed to match
     * what was held even if the hold's shape changes in a later version.
     */
    @Transactional
    public FxAuthorization release(UUID authorizationId, String reason, Instant at) {
        return close(authorizationId, AuthorizationStatus.RELEASED, reason, at == null ? Instant.now() : at);
    }

    /**
     * Expires authorizations whose window has closed.
     *
     * <p>Without this, held funds would stay locked forever on an abandoned quote,
     * and stale authorizations would keep inflating reported exposure.
     */
    @Transactional
    public int expireDue(Instant now, int limit) {
        List<FxAuthorization> due = authorizations.findPendingExpiredAt(now, limit);
        int expired = 0;
        for (FxAuthorization authorization : due) {
            try {
                close(authorization.id(), AuthorizationStatus.EXPIRED, "expired at " + now, now);
                expired++;
            } catch (SettlementException e) {
                // Somebody settled it between the query and the update; that is a
                // legitimate outcome, not an error.
                log.debug("skipped expiring {}: {}", authorization.id(), e.getMessage());
            }
        }
        return expired;
    }

    private FxAuthorization close(UUID authorizationId, AuthorizationStatus status, String reason, Instant at) {
        FxAuthorization authorization = authorizations.findById(authorizationId)
                .orElseThrow(() -> new SettlementException.NotFound(authorizationId));
        if (!authorization.isPending()) {
            throw new SettlementException.WrongState(
                    authorizationId, authorization.status(), AuthorizationStatus.PENDING);
        }

        ledger.reverse(authorization.holdTransactionId(), reason, at);

        if (!authorizations.markClosed(authorizationId, authorization.version(), status, at)) {
            throw new SettlementException.ConcurrentModification(authorizationId);
        }
        released.increment();
        return authorizations.findById(authorizationId).orElseThrow();
    }

    public FxAuthorization require(UUID id) {
        return authorizations.findById(id).orElseThrow(() -> new SettlementException.NotFound(id));
    }

    public List<FxAuthorization> pending() {
        return authorizations.findPending();
    }

    public List<FxAuthorization> forCustomer(String customerId, int limit) {
        return authorizations.findByCustomer(customerId, limit);
    }
}
