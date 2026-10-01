package com.tribule.ledger.settlement;

public class SettlementException extends RuntimeException {

    public SettlementException(String message) {
        super(message);
    }

    public static class NotFound extends SettlementException {
        public NotFound(Object id) {
            super("no such authorization: " + id);
        }
    }

    /** The authorization is no longer in a state where the requested move is legal. */
    public static class WrongState extends SettlementException {
        public WrongState(Object id, AuthorizationStatus actual, AuthorizationStatus required) {
            super("authorization %s is %s, not %s".formatted(id, actual, required));
        }
    }

    /**
     * Two callers tried to move the same authorization out of PENDING at once and
     * this one lost. Detected by a version check on the UPDATE, so the ledger
     * cannot end up with two settlements for one authorization.
     */
    public static class ConcurrentModification extends SettlementException {
        public ConcurrentModification(Object id) {
            super("authorization " + id + " changed underneath this request; re-read it and retry");
        }
    }

    public static class Expired extends SettlementException {
        public Expired(Object id) {
            super("authorization " + id + " has expired and cannot be settled");
        }
    }
}
