package com.tribule.ledger.recon;

import com.tribule.ledger.ledger.Direction;

import java.time.Instant;
import java.util.UUID;

/**
 * One movement as the bank reports it.
 *
 * <p>{@code direction} is from our account's point of view: DEBIT means the balance
 * of our asset went up. Getting that convention backwards is the classic
 * reconciliation bug, so it is stated here and asserted in the tests.
 */
public record StatementLine(
        UUID id,
        UUID batchId,
        int lineNumber,
        String externalRef,
        Instant postedAt,
        String currencyCode,
        long amountMinor,
        Direction direction,
        String description,
        String matchStatus,
        UUID matchedTransactionId) {

    /** The movement as a signed debit-minus-credit amount, comparable to a ledger balance delta. */
    public long signedAmountMinor() {
        return direction.signum() * amountMinor;
    }
}
