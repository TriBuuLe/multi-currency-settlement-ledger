package com.tribule.ledger.ledger;

import java.time.Instant;
import java.util.UUID;

public record JournalTransaction(
        UUID id,
        TransactionKind kind,
        String reference,
        String description,
        /** Valid time: when the economic event happened. */
        Instant occurredAt,
        /** Transaction time: when we wrote it down. */
        Instant recordedAt,
        UUID reversesTransactionId,
        UUID fxRateId,
        String correlationId) {
}
