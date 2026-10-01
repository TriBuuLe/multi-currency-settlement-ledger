package com.tribule.ledger.payments;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Same-currency money movement, and immediate conversion.
 *
 * <p>These are the ordinary operations the FX machinery sits on top of: get money
 * in, move it around, take it out, and convert at today's rate without holding a
 * quote. An immediate conversion carries no settlement risk -- there is no gap
 * between pricing and delivery -- which is what makes it the useful contrast to an
 * authorization.
 */
@Service
public class TransferService {

    public record TransferResult(PostedTransaction transaction, Money amount) {
    }

    public record ConversionResult(
            PostedTransaction transaction,
            Money sold,
            Money bought,
            Money roundingResidual,
            ResolvedRate rate) {
    }

    private final LedgerService ledger;
    private final AccountRepository accounts;
    private final CurrencyRepository currencies;
    private final FxRateService fx;

    public TransferService(LedgerService ledger, AccountRepository accounts,
                          CurrencyRepository currencies, FxRateService fx) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.currencies = currencies;
        this.fx = fx;
    }

    /** Money arriving from the outside world: the bank account goes up, we owe the customer. */
    @Transactional
    public TransferResult fund(String customerId, String currencyCode, long amountMinor,
                               String reference, Instant occurredAt, UUID transactionId) {
        CurrencyUnit currency = requirePositive(currencyCode, amountMinor);
        accounts.ensureCustomerWallet(customerId, currency.code());

        PostedTransaction posted = ledger.post(PostingCommand.of(TransactionKind.FUNDING, at(occurredAt))
                .id(transactionId)
                .reference(reference)
                .description("funding for " + customerId)
                .correlationId(reference)
                .debit(ChartOfAccounts.nostro(currency.code()), amountMinor, "received " + reference)
                .credit(ChartOfAccounts.wallet(customerId, currency.code()), amountMinor, "funded " + reference)
                .build());
        return new TransferResult(posted, Money.ofMinor(amountMinor, currency));
    }

    /** Money leaving: we owe the customer less, the bank account goes down. */
    @Transactional
    public TransferResult payout(String customerId, String currencyCode, long amountMinor,
                                String reference, Instant occurredAt, UUID transactionId) {
        CurrencyUnit currency = requirePositive(currencyCode, amountMinor);
        accounts.ensureCustomerWallet(customerId, currency.code());

        PostedTransaction posted = ledger.post(PostingCommand.of(TransactionKind.TRANSFER, at(occurredAt))
                .id(transactionId)
                .reference(reference)
                .description("payout for " + customerId)
                .correlationId(reference)
                .debit(ChartOfAccounts.wallet(customerId, currency.code()), amountMinor, "paid out " + reference)
                .credit(ChartOfAccounts.nostro(currency.code()), amountMinor, "sent " + reference)
                .build());
        return new TransferResult(posted, Money.ofMinor(amountMinor, currency));
    }

    /** Customer to customer, same currency. Two legs, nothing clever. */
    @Transactional
    public TransferResult transfer(String fromCustomerId, String toCustomerId, String currencyCode,
                                  long amountMinor, String reference, Instant occurredAt, UUID transactionId) {
        CurrencyUnit currency = requirePositive(currencyCode, amountMinor);
        if (fromCustomerId.equals(toCustomerId)) {
            throw new IllegalArgumentException("a transfer needs two different customers");
        }
        accounts.ensureCustomerWallet(fromCustomerId, currency.code());
        accounts.ensureCustomerWallet(toCustomerId, currency.code());

        PostedTransaction posted = ledger.post(PostingCommand.of(TransactionKind.TRANSFER, at(occurredAt))
                .id(transactionId)
                .reference(reference)
                .description("%s -> %s".formatted(fromCustomerId, toCustomerId))
                .correlationId(reference)
                .debit(ChartOfAccounts.wallet(fromCustomerId, currency.code()), amountMinor, "to " + toCustomerId)
                .credit(ChartOfAccounts.wallet(toCustomerId, currency.code()), amountMinor, "from " + fromCustomerId)
                .build());
        return new TransferResult(posted, Money.ofMinor(amountMinor, currency));
    }

    /**
     * Converts one of a customer's balances into another at the current rate.
     *
     * <p>Two currencies in one transaction, balanced in each independently because
     * the halves meet at the FX position account rather than being compared to each
     * other. When the pair had to be triangulated, the pivot rounding residual is
     * posted to the rounding account -- which is the only reason the buy side adds
     * up to the penny.
     */
    @Transactional
    public ConversionResult convert(String customerId, String sellCurrencyCode, String buyCurrencyCode,
                                   long sellAmountMinor, String reference, Instant occurredAt,
                                   UUID transactionId) {
        CurrencyUnit sell = requirePositive(sellCurrencyCode, sellAmountMinor);
        CurrencyUnit buy = currencies.require(buyCurrencyCode);
        if (sell.code().equals(buy.code())) {
            throw new IllegalArgumentException("a conversion needs two different currencies");
        }
        Instant when = at(occurredAt);

        accounts.ensureCustomerWallet(customerId, sell.code());
        accounts.ensureCustomerWallet(customerId, buy.code());

        ResolvedRate rate = fx.resolve(sell.code(), buy.code(), when, when);
        Conversion conversion = fx.convert(Money.ofMinor(sellAmountMinor, sell), buy, rate);
        if (conversion.booked().minorUnits() <= 0) {
            throw new IllegalArgumentException(
                    "%d %s converts to zero %s: below one minor unit at this rate"
                            .formatted(sellAmountMinor, sell.code(), buy.code()));
        }

        PostedTransaction posted = ledger.post(PostingCommand.of(TransactionKind.FX_CONVERSION, when)
                .id(transactionId)
                .reference(reference)
                .description("converted %s to %s for %s".formatted(sell.code(), buy.code(), customerId))
                .fxRate(rate.primaryRateId())
                .correlationId(reference)
                .debit(ChartOfAccounts.wallet(customerId, sell.code()), sellAmountMinor, "sold " + sell.code())
                .credit(ChartOfAccounts.fxPosition(sell.code()), sellAmountMinor, "position " + sell.code())
                .debit(ChartOfAccounts.fxPosition(buy.code()),
                        conversion.market().minorUnits(), "position " + buy.code())
                .credit(ChartOfAccounts.wallet(customerId, buy.code()),
                        conversion.booked().minorUnits(), "bought at " + rate.rate())
                .signed(ChartOfAccounts.fxRounding(buy.code()),
                        -conversion.residual().minorUnits(), "pivot rounding via " + rate.pivotCurrency())
                .build());

        return new ConversionResult(posted, Money.ofMinor(sellAmountMinor, sell),
                conversion.booked(), conversion.residual(), rate);
    }

    private CurrencyUnit requirePositive(String currencyCode, long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amount must be positive, got " + amountMinor);
        }
        return currencies.require(currencyCode);
    }

    private static Instant at(Instant occurredAt) {
        return occurredAt == null ? Instant.now() : occurredAt;
    }
}
