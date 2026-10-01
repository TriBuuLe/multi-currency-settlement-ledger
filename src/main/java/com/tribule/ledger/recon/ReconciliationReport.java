package com.tribule.ledger.recon;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The outcome of reconciling one statement.
 *
 * <p>{@code autoResolutionRate} is the number an operations team actually cares
 * about: of everything that did not match cleanly, what share did the matcher
 * explain and act on without a person looking at it.
 */
public record ReconciliationReport(
        UUID batchId,
        String source,
        String filename,
        Instant reconciledAt,
        int statementLines,
        int matched,
        int breaksDetected,
        int autoResolved,
        int open,
        double autoResolutionRate,
        Map<String, Integer> breaksByType,
        List<ReconciliationBreak> openBreaks,
        List<UUID> adjustmentTransactions,
        /** Suspense balances left behind by auto-resolution: the real open items. */
        Map<String, Long> suspenseBalancesMinor,
        List<String> notes) {

    public ReconciliationReport {
        breaksByType = Map.copyOf(breaksByType);
        openBreaks = List.copyOf(openBreaks);
        adjustmentTransactions = List.copyOf(adjustmentTransactions);
        suspenseBalancesMinor = Map.copyOf(suspenseBalancesMinor);
        notes = List.copyOf(notes);
    }
}
