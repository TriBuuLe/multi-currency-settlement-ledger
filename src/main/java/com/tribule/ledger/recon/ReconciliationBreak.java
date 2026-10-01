package com.tribule.ledger.recon;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationBreak(
        UUID id,
        UUID batchId,
        UUID statementLineId,
        UUID transactionId,
        BreakType breakType,
        String currencyCode,
        /** Ledger minus statement, in minor units. Zero when the break is not about an amount. */
        long deltaMinor,
        BreakStatus status,
        String resolution,
        UUID adjustmentTransactionId,
        Instant detectedAt,
        Instant resolvedAt) {
}
