package com.tribule.ledger.risk;

import com.tribule.ledger.fx.FxRate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One-day value at risk by historical simulation.
 *
 * <p>The method, in full: take consecutive observations of a pair, compute the log
 * return between each adjacent pair of prices, sort them, and read off the return
 * at the {@code (1 - confidence)} percentile. That return is the worst day in the
 * sample once the best {@code confidence} share of days is set aside. Multiply it
 * by the notional at risk and that is the loss figure.
 *
 * <p>Log returns rather than simple returns because they are additive across
 * periods, which is what makes scaling to a multi-day horizon by
 * {@code sqrt(days)} coherent rather than a fudge.
 */
@Component
public class HistoricalVarCalculator {

    /** Below this many returns the percentile is noise, so no number is produced at all. */
    static final int MINIMUM_OBSERVATIONS = 10;

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);

    /**
     * @param history  observations of one pair, oldest first
     * @param notionalReportingMinor exposure the return is applied to
     */
    public VarEstimate estimate(String pair, List<FxRate> history, long notionalReportingMinor,
                                double confidence, int horizonDays) {
        List<Double> returns = logReturns(history);
        if (returns.size() < MINIMUM_OBSERVATIONS) {
            return VarEstimate.insufficient(pair, returns.size(), confidence, horizonDays);
        }

        Collections.sort(returns);
        double worstReturn = percentile(returns, 1.0 - confidence);
        // Log returns are additive, so a multi-day horizon scales with sqrt(t)
        // under the usual independence assumption.
        double scaled = worstReturn * Math.sqrt(horizonDays);
        long var = Math.round(Math.abs(scaled) * Math.abs(notionalReportingMinor));

        return new VarEstimate(
                pair,
                returns.size(),
                confidence,
                horizonDays,
                BigDecimal.valueOf(worstReturn).round(MC),
                var,
                true,
                "historical simulation over %d daily log returns, %.0f%% confidence, %d-day horizon"
                        .formatted(returns.size(), confidence * 100, horizonDays));
    }

    private List<Double> logReturns(List<FxRate> history) {
        List<Double> returns = new ArrayList<>(Math.max(0, history.size() - 1));
        for (int i = 1; i < history.size(); i++) {
            double previous = history.get(i - 1).rate().doubleValue();
            double current = history.get(i).rate().doubleValue();
            if (previous > 0 && current > 0) {
                returns.add(Math.log(current / previous));
            }
        }
        return returns;
    }

    /**
     * Nearest-rank percentile on an ascending list.
     *
     * <p>No interpolation: with a few dozen observations, interpolating between two
     * sampled days invents a return that never happened, and the whole point of
     * historical simulation is that every number in it did.
     */
    static double percentile(List<Double> ascending, double fraction) {
        int index = (int) Math.floor(fraction * ascending.size());
        return ascending.get(Math.clamp(index, 0, ascending.size() - 1));
    }
}
