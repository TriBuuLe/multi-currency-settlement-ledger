package com.tribule.ledger.risk;

import java.math.BigDecimal;

/**
 * Every open authorization on one currency pair, marked to the current rate.
 *
 * <p>{@code unrealizedPnlMinor} is the number that matters: what the house would
 * book if every one of these settled right now. It is the same arithmetic
 * settlement performs, applied to the current rate instead of the settlement
 * rate -- which is why the two cannot disagree about what exposure means.
 */
public record OpenPosition(
        String pair,
        String sellCurrency,
        String buyCurrency,
        int authorizationCount,
        /** Total we will receive, in sell-currency minor units. */
        long sellNotionalMinor,
        /** Total we promised to pay, in buy-currency minor units. */
        long quotedBuyNotionalMinor,
        /** What the sell notional is worth at the current rate, in buy-currency minor units. */
        long markedBuyNotionalMinor,
        /** markedBuyNotional - quotedBuyNotional, in buy-currency minor units. */
        long unrealizedPnlMinor,
        /** The same figure translated into the reporting currency. */
        long unrealizedPnlReportingMinor,
        /** Notional-weighted average of the rates these were quoted at. */
        BigDecimal weightedQuotedRate,
        BigDecimal currentRate,
        /** Oldest open authorization on this pair, in seconds. Old exposure is unmanaged exposure. */
        long oldestAgeSeconds) {

    public boolean isLoss() {
        return unrealizedPnlMinor < 0;
    }
}
