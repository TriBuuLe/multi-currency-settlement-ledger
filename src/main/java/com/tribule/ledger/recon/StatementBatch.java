package com.tribule.ledger.recon;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StatementBatch(
        UUID id,
        String source,
        String filename,
        LocalDate asOfDate,
        int lineCount,
        Instant importedAt) {
}
