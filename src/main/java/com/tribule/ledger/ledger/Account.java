package com.tribule.ledger.ledger;

import com.tribule.ledger.money.CurrencyUnit;

import java.util.UUID;

public record Account(
        UUID id,
        String code,
        String name,
        CurrencyUnit currency,
        AccountType type,
        Direction normalSide,
        boolean contingent,
        boolean allowNegativeBalance) {

    /**
     * Converts a stored debit-minus-credit balance into the sign a reader
     * expects: a liability with a credit balance reads as positive.
     */
    public long toNormalBalance(long signedBalanceMinor) {
        return normalSide == Direction.DEBIT ? signedBalanceMinor : -signedBalanceMinor;
    }
}
