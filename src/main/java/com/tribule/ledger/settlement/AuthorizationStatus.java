package com.tribule.ledger.settlement;

public enum AuthorizationStatus {
    /** Quoted and funded, not yet settled. This is the window that carries FX risk. */
    PENDING,
    /** Settled at a later rate; any rate move has been realized. */
    SETTLED,
    /** Cancelled before settlement; the hold was reversed. */
    RELEASED,
    /** Passed its expiry without settling; the hold was reversed. */
    EXPIRED
}
