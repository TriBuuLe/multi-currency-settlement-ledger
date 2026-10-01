package com.tribule.ledger.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One observation of one price.
 *
 * <p>{@code rate} is how many units of {@code quoteCurrency} one unit of
 * {@code baseCurrency} buys.
 *
 * <p>The two timestamps are the point of this table. {@code effectiveAt} is when
 * the price held in the market; {@code observedAt} is when we found out. They are
 * independent, so a vendor correcting yesterday's bad tick inserts a row with
 * yesterday's {@code effectiveAt} and today's {@code observedAt} -- and every
 * report we produced yesterday can still be reproduced exactly, because the row
 * it used is still there and was never edited.
 */
public record FxRate(
        UUID id,
        String baseCurrency,
        String quoteCurrency,
        BigDecimal rate,
        Instant effectiveAt,
        Instant observedAt,
        String source,
        UUID supersedesId) {

    public String pair() {
        return baseCurrency + "/" + quoteCurrency;
    }

    public boolean isCorrection() {
        return supersedesId != null;
    }
}
