package com.tribule.ledger.ledger;

import java.time.Instant;
import java.util.UUID;

public record AccountBalance(
        UUID accountId,
        String accountCode,
        String currencyCode,
        /** Signed debit-minus-credit. */
        long signedBalanceMinor,
        /** Signed so the account's natural direction reads positive. */
        long normalBalanceMinor,
        long entryCount,
        Long lastEntryId,
        long version,
        Instant updatedAt) {
}
