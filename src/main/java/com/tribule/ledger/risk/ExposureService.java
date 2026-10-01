package com.tribule.ledger.risk;

import com.tribule.ledger.config.LedgerProperties;
import com.tribule.ledger.fx.FxException;
import com.tribule.ledger.fx.FxRate;
import com.tribule.ledger.fx.FxRateService;
import com.tribule.ledger.fx.ResolvedRate;
import com.tribule.ledger.ledger.CurrencyRepository;
import com.tribule.ledger.money.CurrencyUnit;
import com.tribule.ledger.money.Money;
import com.tribule.ledger.settlement.AuthorizationRepository;
import com.tribule.ledger.settlement.FxAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Measures the exposure carried between authorization and settlement.
 *
 * <p>The claim this service has to earn is that "exposure" is not a separate model
 * bolted on next to the ledger. It is the same arithmetic settlement runs, with
 * the current rate substituted for the settlement rate:
 *
 * <pre>
 *   settlement:  realized   = convert(sellAmount, rate at settlement) - quotedAmount
 *   exposure:    unrealized = convert(sellAmount, rate now)           - quotedAmount
 * </pre>
 *
 * <p>Same inputs, same conversion code, same rounding. So the moment an
 * authorization settles, its unrealized number becomes its realized number and the
 * two reports reconcile by construction rather than by luck. An exposure engine
 * that computed this some other way would drift from the books, and a number that
 * disagrees with the ledger is worse than no number.
 */
@Service
public class ExposureService {

    private static final Logger log = LoggerFactory.getLogger(ExposureService.class);
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);

    private final AuthorizationRepository authorizations;
    private final FxRateService fx;
    private final CurrencyRepository currencies;
    private final HistoricalVarCalculator var;
    private final LedgerProperties properties;

    public ExposureService(AuthorizationRepository authorizations,
                          FxRateService fx,
                          CurrencyRepository currencies,
                          HistoricalVarCalculator var,
                          LedgerProperties properties) {
        this.authorizations = authorizations;
        this.fx = fx;
        this.currencies = currencies;
        this.var = var;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ExposureReport report(Instant asOf) {
        Instant at = asOf == null ? Instant.now() : asOf;
        CurrencyUnit reporting = properties.reportingCurrencyUnit();
        List<FxAuthorization> pending = authorizations.findPending();
        List<String> notes = new ArrayList<>();

        Map<String, PairAccumulator> byPair = new LinkedHashMap<>();
        Map<String, long[]> netByCurrency = new TreeMap<>();

        for (FxAuthorization authorization : pending) {
            // A pair whose rate we cannot resolve right now is reported as a gap
            // rather than silently omitted -- an exposure report with a quiet hole
            // in it is worse than one that says it is incomplete.
            ResolvedRate current;
            try {
                current = fx.resolve(authorization.sellCurrency(), authorization.buyCurrency(), at, at);
            } catch (FxException e) {
                notes.add("excluded %s: %s".formatted(authorization.reference(), e.getMessage()));
                continue;
            }

            CurrencyUnit sell = currencies.require(authorization.sellCurrency());
            CurrencyUnit buy = currencies.require(authorization.buyCurrency());
            Money sellAmount = Money.ofMinor(authorization.sellAmountMinor(), sell);
            long markedMinor = fx.convert(sellAmount, buy, current).booked().minorUnits();

            byPair.computeIfAbsent(authorization.pair(), p -> new PairAccumulator(
                            authorization.sellCurrency(), authorization.buyCurrency(), current.rate()))
                    .add(authorization, markedMinor, at);

            // Long the currency we are owed, short the one we promised.
            netByCurrency.computeIfAbsent(authorization.sellCurrency(), c -> new long[2])[0]
                    += authorization.sellAmountMinor();
            netByCurrency.computeIfAbsent(authorization.sellCurrency(), c -> new long[2])[1] += 1;
            netByCurrency.computeIfAbsent(authorization.buyCurrency(), c -> new long[2])[0]
                    -= authorization.quotedBuyAmountMinor();
            netByCurrency.computeIfAbsent(authorization.buyCurrency(), c -> new long[2])[1] += 1;
        }

        List<OpenPosition> positions = new ArrayList<>();
        long totalUnrealizedReporting = 0;
        for (PairAccumulator accumulator : byPair.values()) {
            long unrealizedReporting = translate(
                    accumulator.unrealizedMinor(), accumulator.buyCurrency, reporting, at, notes);
            totalUnrealizedReporting += unrealizedReporting;
            positions.add(accumulator.toPosition(unrealizedReporting, at));
        }
        positions.sort(Comparator.comparingLong(OpenPosition::unrealizedPnlReportingMinor));

        List<CurrencyExposure> exposures = new ArrayList<>();
        long gross = 0;
        for (Map.Entry<String, long[]> entry : netByCurrency.entrySet()) {
            long net = entry.getValue()[0];
            long netReporting = translate(net, entry.getKey(), reporting, at, notes);
            gross += Math.abs(netReporting);
            exposures.add(new CurrencyExposure(entry.getKey(), net, netReporting, (int) entry.getValue()[1]));
        }

        List<VarEstimate> estimates = new ArrayList<>();
        long portfolioVar = 0;
        for (PairAccumulator accumulator : byPair.values()) {
            long notionalReporting = translate(
                    accumulator.markedBuyNotionalMinor, accumulator.buyCurrency, reporting, at, notes);
            List<FxRate> history = fx.history(
                    accumulator.sellCurrency, accumulator.buyCurrency,
                    at.minus(Duration.ofDays(properties.varLookbackDays())), at, at);
            VarEstimate estimate = var.estimate(accumulator.pair(), history, notionalReporting,
                    properties.varConfidence(), 1);
            estimates.add(estimate);
            portfolioVar += estimate.varReportingMinor();
        }
        if (!estimates.isEmpty()) {
            notes.add("portfolio VaR is the sum of per-pair figures: no correlation benefit is assumed, "
                    + "which is conservative for offsetting pairs and optimistic for pairs that move together");
        }
        if (pending.isEmpty()) {
            notes.add("no open authorizations: there is no settlement risk to report");
        }

        return new ExposureReport(at, reporting.code(), positions, exposures, pending.size(),
                totalUnrealizedReporting, gross, estimates, portfolioVar, notes);
    }

    /** Values an amount in the reporting currency, recording a note if it cannot be. */
    private long translate(long amountMinor, String currency, CurrencyUnit reporting,
                           Instant at, List<String> notes) {
        if (amountMinor == 0) {
            return 0;
        }
        if (currency.equals(reporting.code())) {
            return amountMinor;
        }
        try {
            CurrencyUnit from = currencies.require(currency);
            ResolvedRate rate = fx.resolve(currency, reporting.code(), at, at);
            return fx.convert(Money.ofMinor(amountMinor, from), reporting, rate).booked().minorUnits();
        } catch (FxException e) {
            notes.add("could not value %s in %s: %s".formatted(currency, reporting.code(), e.getMessage()));
            log.debug("translation to reporting currency failed for {}", currency, e);
            return 0;
        }
    }

    /** Running totals for one pair while the report is being built. */
    private static final class PairAccumulator {
        private final String sellCurrency;
        private final String buyCurrency;
        private final BigDecimal currentRate;
        private int count;
        private long sellNotionalMinor;
        private long quotedBuyNotionalMinor;
        private long markedBuyNotionalMinor;
        private BigDecimal weightedRateNumerator = BigDecimal.ZERO;
        private Instant oldest;

        private PairAccumulator(String sellCurrency, String buyCurrency, BigDecimal currentRate) {
            this.sellCurrency = sellCurrency;
            this.buyCurrency = buyCurrency;
            this.currentRate = currentRate;
        }

        private void add(FxAuthorization authorization, long markedMinor, Instant at) {
            count++;
            sellNotionalMinor += authorization.sellAmountMinor();
            quotedBuyNotionalMinor += authorization.quotedBuyAmountMinor();
            markedBuyNotionalMinor += markedMinor;
            weightedRateNumerator = weightedRateNumerator.add(
                    authorization.quotedRate().multiply(BigDecimal.valueOf(authorization.sellAmountMinor())));
            if (oldest == null || authorization.authorizedAt().isBefore(oldest)) {
                oldest = authorization.authorizedAt();
            }
        }

        private long unrealizedMinor() {
            return markedBuyNotionalMinor - quotedBuyNotionalMinor;
        }

        private String pair() {
            return sellCurrency + "/" + buyCurrency;
        }

        private OpenPosition toPosition(long unrealizedReportingMinor, Instant at) {
            BigDecimal weighted = sellNotionalMinor == 0
                    ? BigDecimal.ZERO
                    : weightedRateNumerator.divide(BigDecimal.valueOf(sellNotionalMinor), MC);
            long age = oldest == null ? 0 : Math.max(0, Duration.between(oldest, at).toSeconds());
            return new OpenPosition(pair(), sellCurrency, buyCurrency, count,
                    sellNotionalMinor, quotedBuyNotionalMinor, markedBuyNotionalMinor,
                    unrealizedMinor(), unrealizedReportingMinor, weighted, currentRate, age);
        }
    }
}
