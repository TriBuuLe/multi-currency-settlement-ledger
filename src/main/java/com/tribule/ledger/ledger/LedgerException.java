package com.tribule.ledger.ledger;

/** Base class for domain failures that should surface as 4xx, not 500. */
public class LedgerException extends RuntimeException {

    public LedgerException(String message) {
        super(message);
    }

    public LedgerException(String message, Throwable cause) {
        super(message, cause);
    }

    /** A transaction does not balance, per currency. */
    public static class Unbalanced extends LedgerException {
        public Unbalanced(String message) {
            super(message);
        }
    }

    /** An account code does not exist. */
    public static class AccountNotFound extends LedgerException {
        public AccountNotFound(String code) {
            super("no such account: " + code);
        }
    }

    /** A posting would overdraw an account that is not allowed to go negative. */
    public static class InsufficientFunds extends LedgerException {
        public InsufficientFunds(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The requested transaction does not exist. */
    public static class TransactionNotFound extends LedgerException {
        public TransactionNotFound(Object id) {
            super("no such transaction: " + id);
        }
    }

    /** A reversal was requested for a transaction that already has one. */
    public static class AlreadyReversed extends LedgerException {
        public AlreadyReversed(Object id) {
            super("transaction " + id + " has already been reversed");
        }
    }
}
