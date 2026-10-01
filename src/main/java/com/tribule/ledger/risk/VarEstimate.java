package com.tribule.ledger.risk;

import java.math.BigDecimal;

/**
 * A one-day historical-simulation value at risk.
 *
 * <p>Deliberately the simplest defensible method: take the observed daily returns
 * of the pair over the lookback window, find the loss at the chosen percentile,
 * and apply it to the current notional. No distributional assumption, no
 * volatility model.
 *
 * <p>What it is not: it assumes the next day looks like the sampled window, it
 * says nothing about the tail beyond the percentile, and the portfolio figure
 * sums the per-pair numbers with no correlation benefit -- which overstates risk
 * when pairs offset and understates it when they move together. Those limits are
 * recorded in {@code method} so the number is never read as more than it is.
 */
public record VarEstimate(
        String pair,
        int observations,
        double confidence,
        int horizonDays,
        /** The return at the chosen percentile. Negative. */
        BigDecimal worstReturn,
        /** Potential loss over the horizon, in reporting-currency minor units. Positive. */
        long varReportingMinor,
        boolean sufficientData,
        String method) {

    public static VarEstimate insufficient(String pair, int observations, double confidence, int horizonDays) {
        return new VarEstimate(pair, observations, confidence, horizonDays, null, 0, false,
                "historical simulation: not enough rate history to estimate");
    }
}
