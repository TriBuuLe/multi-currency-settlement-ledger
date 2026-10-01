package com.tribule.ledger.recon;

public class ReconciliationException extends RuntimeException {

    public ReconciliationException(String message) {
        super(message);
    }

    public static class BatchNotFound extends ReconciliationException {
        public BatchNotFound(Object id) {
            super("no such statement batch: " + id);
        }
    }

    /** The same file was submitted twice. Re-importing it would double-count every line. */
    public static class DuplicateBatch extends ReconciliationException {
        public DuplicateBatch(String source, String filename) {
            super("statement '%s' from %s has already been imported".formatted(filename, source));
        }
    }

    public static class MalformedStatement extends ReconciliationException {
        public MalformedStatement(int line, String detail) {
            super("statement line %d is malformed: %s".formatted(line, detail));
        }
    }
}
