package com.tribule.ledger.money;

/** Thrown when an amount is not representable, or when two currencies are mixed. */
public class MoneyException extends RuntimeException {
    public MoneyException(String message) {
        super(message);
    }
}
