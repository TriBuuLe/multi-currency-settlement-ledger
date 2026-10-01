package com.tribule.ledger.idempotency;

/** Idempotency failures that are the client's to resolve, not the server's. */
public class IdempotencyException extends RuntimeException {

    public IdempotencyException(String message) {
        super(message);
    }

    /**
     * The key has been seen before with a different request body.
     *
     * <p>This is always a client bug and is the one case that must never be
     * papered over: returning the first request's result for a second, different
     * request is how a 10 USD transfer gets reported as a successful 10,000 USD
     * transfer.
     */
    public static class KeyReused extends IdempotencyException {
        public KeyReused(String scope, String key) {
            super("idempotency key '%s' (%s) was already used with a different request body".formatted(key, scope));
        }
    }

    /** An earlier attempt with this key is still running. */
    public static class InProgress extends IdempotencyException {
        public InProgress(String scope, String key) {
            super("an earlier request with idempotency key '%s' (%s) is still in flight; retry shortly"
                    .formatted(key, scope));
        }
    }
}
