package com.tribule.ledger.ledger;

import java.util.UUID;

public record JournalEntry(
        long id,
        UUID transactionId,
        UUID accountId,
        String accountCode,
        String currencyCode,
        Direction direction,
        long amountMinor,
        short entrySeq,
        String memo) {

    /** The entry's contribution to a debit-minus-credit balance. */
    public long signedAmountMinor() {
        return direction.signum() * amountMinor;
    }
}
