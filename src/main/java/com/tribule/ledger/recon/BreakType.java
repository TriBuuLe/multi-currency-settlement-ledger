package com.tribule.ledger.recon;

/**
 * Why the ledger and the statement disagree.
 *
 * <p>Classifying the disagreement is the point. "We are out by 4,312 JPY" is a
 * number nobody can act on; "the bank sent this reference twice and this other
 * payment has not reached them yet" is two tickets with two different owners.
 */
public enum BreakType {
    /** The bank has a movement we never booked. Usually an inbound payment we were not told about. */
    MISSING_IN_LEDGER,
    /** We booked a movement the bank has not reported. Usually in flight, sometimes lost. */
    MISSING_IN_STATEMENT,
    /** Same reference on both sides, different money. Fees, partial fills, or a real error. */
    AMOUNT_MISMATCH,
    /** The same reference appears more than once in one statement file. */
    DUPLICATE_IN_STATEMENT,
    /** Matched on reference and amount, but the bank's value date is outside tolerance. */
    TIMING_DIFFERENCE
}
