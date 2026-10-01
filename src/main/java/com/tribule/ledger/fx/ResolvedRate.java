package com.tribule.ledger.fx;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A usable rate for a pair, plus how it was obtained.
 *
 * <p>The legs are kept rather than collapsed into a single number because a
 * triangulated conversion has to round at the pivot, and the residual that
 * creates is real money that has to be posted somewhere. Throwing the legs away
 * and keeping only the composite rate would quietly hide it.
 */
public record ResolvedRate(
        String baseCurrency,
        String quoteCurrency,
        BigDecimal rate,
        RateResolution resolution,
        /** The rate row recorded against the journal transaction. */
        UUID primaryRateId,
        String pivotCurrency,
        BigDecimal firstLegRate,
        BigDecimal secondLegRate,
        List<UUID> legRateIds) {

    public ResolvedRate {
        legRateIds = legRateIds == null ? List.of() : List.copyOf(legRateIds);
    }

    public boolean isTriangulated() {
        return resolution == RateResolution.TRIANGULATED;
    }

    public String pair() {
        return baseCurrency + "/" + quoteCurrency;
    }

    static ResolvedRate identity(String currency) {
        return new ResolvedRate(currency, currency, BigDecimal.ONE, RateResolution.IDENTITY,
                null, null, null, null, List.of());
    }

    static ResolvedRate direct(FxRate rate) {
        return new ResolvedRate(rate.baseCurrency(), rate.quoteCurrency(), rate.rate(),
                RateResolution.DIRECT, rate.id(), null, null, null, List.of(rate.id()));
    }

    static ResolvedRate inverse(FxRate quoted, BigDecimal inverted) {
        return new ResolvedRate(quoted.quoteCurrency(), quoted.baseCurrency(), inverted,
                RateResolution.INVERSE, quoted.id(), null, null, null, List.of(quoted.id()));
    }

    static ResolvedRate triangulated(String base, String quote, String pivot,
                                     BigDecimal firstLeg, BigDecimal secondLeg,
                                     BigDecimal composite, UUID primaryRateId, List<UUID> legIds) {
        return new ResolvedRate(base, quote, composite, RateResolution.TRIANGULATED,
                primaryRateId, pivot, firstLeg, secondLeg, legIds);
    }
}
