package com.tribule.ledger.settlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A promise to exchange currency at a price agreed now and delivered later.
 *
 * <p>Between {@code authorizedAt} and {@code settledAt} the house is on the hook
 * for the difference between {@code quotedRate} and whatever the rate turns out
 * to be. That gap is the entire subject of the exposure endpoints: it is not a
 * modelling abstraction, it is the number that decides whether the spread charged
 * on the quote was enough.
 */
public record FxAuthorization(
        UUID id,
        String reference,
        String customerId,
        String sellCurrency,
        String buyCurrency,
        long sellAmountMinor,
        long quotedBuyAmountMinor,
        BigDecimal quotedRate,
        UUID quotedRateId,
        AuthorizationStatus status,
        Instant authorizedAt,
        Instant expiresAt,
        Instant settledAt,
        UUID holdTransactionId,
        UUID settlementTransactionId,
        BigDecimal settlementRate,
        UUID settlementRateId,
        Long realizedPnlMinor,
        String realizedPnlCurrency,
        int version) {

    public String pair() {
        return sellCurrency + "/" + buyCurrency;
    }

    public boolean isPending() {
        return status == AuthorizationStatus.PENDING;
    }
}
