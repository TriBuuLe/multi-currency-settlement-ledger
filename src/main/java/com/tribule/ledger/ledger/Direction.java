package com.tribule.ledger.ledger;

/** Which side of the journal an entry sits on. Amounts are always positive; this carries the sign. */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    /** +1 for a debit, -1 for a credit: the sign convention used for stored balances. */
    public int signum() {
        return this == DEBIT ? 1 : -1;
    }
}
