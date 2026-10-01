package com.tribule.ledger.fx;

import java.time.Instant;

public class FxException extends RuntimeException {

    public FxException(String message) {
        super(message);
    }

    /** No rate exists for the pair at that point in valid and transaction time. */
    public static class RateNotAvailable extends FxException {
        public RateNotAvailable(String base, String quote, Instant effectiveAt, Instant knownAt) {
            super("no rate for %s/%s effective at or before %s, as known at %s (directly, inverted, or via a pivot)"
                    .formatted(base, quote, effectiveAt, knownAt));
        }
    }

    /** A correction was published against a rate that does not exist. */
    public static class UnknownRate extends FxException {
        public UnknownRate(Object id) {
            super("no such fx rate: " + id);
        }
    }
}
