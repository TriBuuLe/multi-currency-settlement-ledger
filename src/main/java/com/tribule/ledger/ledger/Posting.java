package com.tribule.ledger.ledger;

/** One requested leg of a transaction, addressed by account code. */
public record Posting(String accountCode, Direction direction, long amountMinor, String memo) {

    public Posting {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "posting amount must be positive (use direction for sign), got " + amountMinor);
        }
    }

    public static Posting debit(String accountCode, long amountMinor, String memo) {
        return new Posting(accountCode, Direction.DEBIT, amountMinor, memo);
    }

    public static Posting credit(String accountCode, long amountMinor, String memo) {
        return new Posting(accountCode, Direction.CREDIT, amountMinor, memo);
    }
}
