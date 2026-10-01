package com.tribule.ledger.ledger;

/**
 * One currency's trial balance.
 *
 * <p>{@code residualMinor} is the sum of every signed balance in that currency.
 * In a correct double-entry ledger it is always zero -- including across
 * currencies, because each currency balances on its own.
 */
public record TrialBalanceLine(
        String currencyCode,
        long totalDebitsMinor,
        long totalCreditsMinor,
        long residualMinor,
        long accountCount) {

    public boolean isBalanced() {
        return residualMinor == 0L;
    }
}
