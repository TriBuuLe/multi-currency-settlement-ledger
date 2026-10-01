package com.tribule.ledger.settlement;

import com.tribule.ledger.fx.FxRateService;
import com.tribule.ledger.fx.ResolvedRate;
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

import java.time.Instant;
import java.util.UUID;

/**
 * Settles an authorization, and in doing so turns FX exposure into FX P&amp;L.
 *
 * <h2>Why a currency conversion does not break double-entry</h2>
 *
 * <p>A single transaction moves two currencies, and it balances in <em>each</em>
 * one independently. That is only possible because the two halves are joined
 * through a pivot account -- {@code EQUITY:FX_POSITION} -- rather than by pretending
 * that some number of euros equals some number of yen. The sell half credits the
 * position account in the sell currency; the buy half debits it in the buy
 * currency. Neither half references the other's units, so neither half can be
 * unbalanced by a rate.
 *
 * <h2>Where the rate move goes</h2>
 *
 * <p>The customer was quoted {@code quotedBuyAmount} at authorization time and is
 * paid exactly that -- honouring the quote is the product. The market, at
 * settlement time, delivers something else. The difference is not an error to be
 * rounded away; it is the economic result of having carried the position, and it
 * is posted to {@code EQUITY:FX_REALIZED_PNL} in the buy currency:
 *
 * <pre>
 *   realized P&amp;L = (what the market delivers at settlement)
 *                  - (what the customer was promised at authorization)
 * </pre>
 *
 * <p>A positive number means the move went the house's way. A negative one means
 * the spread on the quote was not wide enough -- which is exactly the number a
 * pricing desk needs, and it is only visible because it was given its own account
 * instead of being buried in a customer balance.
 *
 * <h2>Rounding</h2>
 *
 * <p>When the pair had to be triangulated, the pivot leg was rounded to a bookable
 * amount, so the two-leg result differs from the exact cross rate by a whole
 * minor unit or two. That residual is posted to {@code EQUITY:FX_ROUNDING} rather
 * than absorbed into P&amp;L, so rounding drift stays separable from trading
 * result when someone asks where the money went.
 */
@Service
public class SettlementService {

    public record SettlementResult(
            FxAuthorization authorization,
            PostedTransaction settlementTransaction,
            ResolvedRate settlementRate,
            /** Signed, in the buy currency. Positive means the house gained. */
            Money realizedPnl,
            /** Rounding drift from triangulating the pair, in the buy currency. */
            Money roundingResidual,
            Money delivered,
            Money marketValue) {
    }

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final AuthorizationRepository authorizations;
    private final CurrencyRepository currencies;
    private final LedgerService ledger;
    private final FxRateService fx;
    private final Counter settled;
    private final Counter gains;
    private final Counter losses;

    public SettlementService(AuthorizationRepository authorizations,
                            CurrencyRepository currencies,
                            LedgerService ledger,
                            FxRateService fx,
                            MeterRegistry registry) {
        this.authorizations = authorizations;
        this.currencies = currencies;
        this.ledger = ledger;
        this.fx = fx;
        this.settled = Counter.builder("ledger.settlements.completed").register(registry);
        this.gains = Counter.builder("ledger.settlements.fx_gain_minor")
                .description("Realized FX gain, in minor units of the buy currency")
                .register(registry);
        this.losses = Counter.builder("ledger.settlements.fx_loss_minor")
                .description("Realized FX loss, in minor units of the buy currency")
                .register(registry);
    }

    @Transactional
    public SettlementResult settle(UUID authorizationId, Instant settledAt, UUID reservedTransactionId) {
        Instant at = settledAt == null ? Instant.now() : settledAt;
        UUID transactionId = reservedTransactionId == null ? UUID.randomUUID() : reservedTransactionId;

        FxAuthorization authorization = authorizations.findById(authorizationId)
                .orElseThrow(() -> new SettlementException.NotFound(authorizationId));
        if (!authorization.isPending()) {
            throw new SettlementException.WrongState(
                    authorizationId, authorization.status(), AuthorizationStatus.PENDING);
        }
        if (at.isAfter(authorization.expiresAt())) {
            throw new SettlementException.Expired(authorizationId);
        }

        CurrencyUnit sell = currencies.require(authorization.sellCurrency());
        CurrencyUnit buy = currencies.require(authorization.buyCurrency());

        ResolvedRate settlementRate = fx.resolve(sell.code(), buy.code(), at, at);
        Money sellAmount = Money.ofMinor(authorization.sellAmountMinor(), sell);
        Conversion atSettlement = fx.convert(sellAmount, buy, settlementRate);

        long bookedMinor = atSettlement.booked().minorUnits();
        long marketMinor = atSettlement.market().minorUnits();
        long residualMinor = atSettlement.residual().minorUnits();
        long quotedMinor = authorization.quotedBuyAmountMinor();
        long realizedPnlMinor = bookedMinor - quotedMinor;

        String customer = authorization.customerId();

        // Claim the state transition FIRST.
        //
        // Only one settlement per authorization, decided by a version check. Taking
        // the claim before posting means a request that has lost the race fails here,
        // cleanly, instead of posting legs that drive the hold account negative and
        // then reporting "insufficient funds" -- which is true but is not the reason.
        // The settlement_transaction_id foreign key is deferred precisely so this row
        // can point at a transaction that is written a few lines below.
        boolean claimed = authorizations.markSettled(
                authorization.id(), authorization.version(), transactionId,
                settlementRate.rate(), settlementRate.primaryRateId(),
                realizedPnlMinor, buy.code(), at);
        if (!claimed) {
            throw new SettlementException.ConcurrentModification(authorizationId);
        }

        PostedTransaction settlement = ledger.post(
                PostingCommand.of(TransactionKind.SETTLEMENT, at)
                        .id(transactionId)
                        .reference(authorization.reference())
                        .description("settlement of authorization " + authorization.reference())
                        .fxRate(settlementRate.primaryRateId())
                        .correlationId(authorization.reference())

                        // --- sell currency: the hold becomes ours -------------------
                        .debit(ChartOfAccounts.hold(customer, sell.code()),
                                authorization.sellAmountMinor(), "hold released on settlement")
                        .credit(ChartOfAccounts.fxPosition(sell.code()),
                                authorization.sellAmountMinor(), "acquired against " + authorization.reference())

                        // --- buy currency: what the market gives, split three ways --
                        .debit(ChartOfAccounts.fxPosition(buy.code()),
                                marketMinor, "market value at settlement rate")
                        // The customer gets exactly what was quoted.
                        .credit(ChartOfAccounts.wallet(customer, buy.code()),
                                quotedMinor, "settled at quoted rate " + authorization.quotedRate())
                        // The rate move since authorization -- debit on a loss, credit on a gain.
                        .signed(ChartOfAccounts.fxRealizedPnl(buy.code()),
                                -realizedPnlMinor, "rate move " + authorization.quotedRate() + " -> " + settlementRate.rate())
                        // Triangulation drift, kept separate from trading result.
                        .signed(ChartOfAccounts.fxRounding(buy.code()),
                                -residualMinor, "pivot rounding via " + settlementRate.pivotCurrency())

                        // --- buy currency: the commitment is discharged -------------
                        .debit(ChartOfAccounts.fxCommitment(buy.code()),
                                quotedMinor, "commitment discharged")
                        .credit(ChartOfAccounts.fxCommitmentContra(buy.code()),
                                quotedMinor, "commitment discharged")
                        .build());

        settled.increment();
        if (realizedPnlMinor >= 0) {
            gains.increment(realizedPnlMinor);
        } else {
            losses.increment(-realizedPnlMinor);
        }
        log.debug("settled {} quoted={} market={} pnl={} {}", authorization.reference(),
                quotedMinor, bookedMinor, realizedPnlMinor, buy.code());

        return new SettlementResult(
                authorizations.findById(authorization.id()).orElseThrow(),
                settlement,
                settlementRate,
                Money.ofMinor(realizedPnlMinor, buy),
                Money.ofMinor(residualMinor, buy),
                Money.ofMinor(quotedMinor, buy),
                Money.ofMinor(marketMinor, buy));
    }
}
