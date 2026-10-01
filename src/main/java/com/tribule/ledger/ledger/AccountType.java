package com.tribule.ledger.ledger;

public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE,
    /** Off-balance-sheet: commitments such as open FX authorizations. */
    CONTINGENT
}
