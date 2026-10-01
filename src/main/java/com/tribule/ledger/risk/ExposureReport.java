package com.tribule.ledger.risk;

import java.time.Instant;
import java.util.List;

/**
 * Settlement risk at a point in time.
 *
 * <p>Read top to bottom: which pairs are open and what they are worth now, what
 * that nets to per currency, what it all adds up to in the reporting currency,
 * and how bad a normal bad day would be.
 */
public record ExposureReport(
        Instant asOf,
        String reportingCurrency,
        List<OpenPosition> positions,
        List<CurrencyExposure> netByCurrency,
        int openAuthorizationCount,
        /** Total mark-to-market on open authorizations, in reporting-currency minor units. */
        long totalUnrealizedPnlReportingMinor,
        /** Sum of absolute net positions: how much currency risk is on the books at all. */
        long grossExposureReportingMinor,
        List<VarEstimate> valueAtRisk,
        long portfolioVarReportingMinor,
        List<String> notes) {

    public ExposureReport {
        positions = List.copyOf(positions);
        netByCurrency = List.copyOf(netByCurrency);
        valueAtRisk = List.copyOf(valueAtRisk);
        notes = List.copyOf(notes);
    }
}
