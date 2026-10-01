package com.tribule.ledger.fx;

/** How a rate for a pair was arrived at. Recorded because it changes the rounding behaviour. */
public enum RateResolution {
    /** Same currency: rate of exactly 1. */
    IDENTITY,
    /** The pair is quoted directly. */
    DIRECT,
    /** Only the opposite pair is quoted, so the rate is its reciprocal. */
    INVERSE,
    /** Neither direction is quoted; the rate is composed through a pivot currency. */
    TRIANGULATED
}
