package com.tribule.ledger.ledger;

/**
 * Account codes, derived rather than typed.
 *
 * <p>Account codes are structural, and a typo in one is a posting that lands in
 * the wrong place. Building them here means the compiler is involved and the
 * seeded chart in V8 and the code that posts to it cannot drift apart.
 */
public final class ChartOfAccounts {

    private ChartOfAccounts() {
    }

    /** House cash at the correspondent bank -- what the bank statement reconciles against. */
    public static String nostro(String currency) {
        return "ASSET:NOSTRO:" + currency;
    }

    /** Where unidentified movements park until somebody works out what they were. */
    public static String suspense(String currency) {
        return "ASSET:SUSPENSE:" + currency;
    }

    /** A customer's spendable balance. */
    public static String wallet(String customerId, String currency) {
        return "LIABILITY:CUSTOMER:" + customerId + ":" + currency;
    }

    /**
     * A customer's funds committed to an open authorization.
     *
     * <p>Splitting this out from the wallet is what makes an "available balance"
     * mean something: money behind an open authorization is still the customer's,
     * but it cannot be spent twice.
     */
    public static String hold(String customerId, String currency) {
        return "LIABILITY:CUSTOMER_HOLD:" + customerId + ":" + currency;
    }

    /** The pivot every conversion passes through, and the house's FX position. */
    public static String fxPosition(String currency) {
        return "EQUITY:FX_POSITION:" + currency;
    }

    /** Where the authorization-to-settlement rate move lands once it is a fact. */
    public static String fxRealizedPnl(String currency) {
        return "EQUITY:FX_REALIZED_PNL:" + currency;
    }

    /** Where sub-unit remainders from triangulated conversions land. */
    public static String fxRounding(String currency) {
        return "EQUITY:FX_ROUNDING:" + currency;
    }

    /** Off-balance-sheet commitment for an open authorization. */
    public static String fxCommitment(String currency) {
        return "CONTINGENT:FX_COMMITMENT:" + currency;
    }

    /** The other side of {@link #fxCommitment(String)}, so contingent postings balance too. */
    public static String fxCommitmentContra(String currency) {
        return "CONTINGENT:FX_COMMITMENT_CONTRA:" + currency;
    }
}
