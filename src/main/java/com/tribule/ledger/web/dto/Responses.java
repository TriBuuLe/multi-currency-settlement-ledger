package com.tribule.ledger.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound payloads.
 *
 * <p>Every monetary figure ships as minor units <em>and</em> a formatted string.
 * The integer is what a caller should compute with; the string is what a human
 * reads in a log or a screenshot, and having both means nobody has to guess the
 * currency's scale to interpret the number.
 *
 * <p>These are also what gets stored for idempotent replay, so they are plain data
 * with no domain types in them -- a replayed response has to deserialise cleanly
 * weeks later, including after the domain model has moved on.
 */
public final class Responses {

    private Responses() {
    }

    public record MoneyView(long amountMinor, String currency, String amount) {
    }

    public record EntryView(long id, String accountCode, String currency,
                            String direction, long amountMinor, String memo) {
    }

    public record TransactionView(
            UUID id,
            String kind,
            String reference,
            String description,
            Instant occurredAt,
            Instant recordedAt,
            UUID reversesTransactionId,
            UUID fxRateId,
            List<EntryView> entries) {
    }

    public record RateView(
            String pair,
            BigDecimal rate,
            String resolution,
            UUID rateId,
            String pivotCurrency,
            BigDecimal firstLegRate,
            BigDecimal secondLegRate) {
    }

    public record FxRateView(
            UUID id,
            String pair,
            BigDecimal rate,
            Instant effectiveAt,
            Instant observedAt,
            String source,
            UUID supersedesId) {
    }

    public record TransferView(TransactionView transaction, MoneyView amount) {
    }

    public record ConversionView(
            TransactionView transaction,
            MoneyView sold,
            MoneyView bought,
            MoneyView roundingResidual,
            RateView rate) {
    }

    public record AuthorizationView(
            UUID id,
            String reference,
            String customerId,
            String pair,
            MoneyView selling,
            MoneyView quoted,
            BigDecimal quotedRate,
            UUID quotedRateId,
            String status,
            Instant authorizedAt,
            Instant expiresAt,
            Instant settledAt,
            UUID holdTransactionId,
            UUID settlementTransactionId,
            BigDecimal settlementRate,
            MoneyView realizedPnl) {
    }

    public record AuthorizationCreatedView(
            AuthorizationView authorization,
            TransactionView holdTransaction,
            RateView quotedRate) {
    }

    public record SettlementView(
            AuthorizationView authorization,
            TransactionView settlementTransaction,
            RateView settlementRate,
            MoneyView delivered,
            MoneyView marketValue,
            MoneyView realizedPnl,
            MoneyView roundingResidual,
            String explanation) {
    }

    public record BalanceView(
            String accountCode,
            String currency,
            long signedBalanceMinor,
            long normalBalanceMinor,
            String balance,
            long entryCount,
            Long lastEntryId,
            long version,
            Instant updatedAt) {
    }

    public record TrialBalanceView(
            String currency,
            long debitBalancesMinor,
            long creditBalancesMinor,
            long residualMinor,
            long accountCount,
            boolean balanced) {
    }

    public record RebuildView(
            String accountCode,
            long projectedBalanceMinor,
            long rebuiltBalanceMinor,
            boolean matches,
            long entriesReplayed,
            long fromSnapshotEntryId) {
    }

    public record VerificationView(
            Instant verifiedAt,
            boolean healthy,
            long transactions,
            long entries,
            long durationMillis,
            List<TrialBalanceView> trialBalance,
            List<FindingView> findings) {
    }

    public record FindingView(String check, String severity, String detail, List<String> samples) {
    }

    public record OpenPositionView(
            String pair,
            int authorizationCount,
            MoneyView selling,
            MoneyView quoted,
            MoneyView markedToMarket,
            MoneyView unrealizedPnl,
            long unrealizedPnlReportingMinor,
            BigDecimal weightedQuotedRate,
            BigDecimal currentRate,
            long oldestAgeSeconds) {
    }

    public record CurrencyExposureView(
            String currency,
            long netPositionMinor,
            long netPositionReportingMinor,
            String direction,
            int authorizationCount) {
    }

    public record VarView(
            String pair,
            int observations,
            double confidence,
            int horizonDays,
            BigDecimal worstReturn,
            long varReportingMinor,
            boolean sufficientData,
            String method) {
    }

    public record ExposureView(
            Instant asOf,
            String reportingCurrency,
            int openAuthorizations,
            long totalUnrealizedPnlReportingMinor,
            long grossExposureReportingMinor,
            long portfolioVarReportingMinor,
            List<OpenPositionView> positions,
            List<CurrencyExposureView> netByCurrency,
            List<VarView> valueAtRisk,
            List<String> notes) {
    }

    public record StatementBatchView(
            UUID id, String source, String filename, String asOfDate, int lineCount, Instant importedAt) {
    }

    public record BreakView(
            UUID id,
            String breakType,
            String currency,
            long deltaMinor,
            String status,
            String resolution,
            UUID statementLineId,
            UUID transactionId,
            UUID adjustmentTransactionId,
            Instant detectedAt) {
    }

    public record ReconciliationView(
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
            List<BreakView> openBreaks,
            List<UUID> adjustmentTransactions,
            Map<String, Long> suspenseBalancesMinor,
            List<String> notes) {
    }

    public record CorrectionReplayView(
            UUID correctionRateId,
            UUID supersededRateId,
            String pair,
            Instant effectiveAt,
            int settlementsExamined,
            int adjustmentsPosted,
            long netPnlDeltaMinor,
            List<AdjustmentView> adjustments,
            List<String> notes) {
    }

    public record AdjustmentView(
            UUID authorizationId,
            String reference,
            long originalPnlMinor,
            long correctedPnlMinor,
            long pnlDeltaMinor,
            String currency,
            UUID adjustmentTransactionId) {
    }

    public record AccountView(
            UUID id, String code, String name, String currency, String type,
            String normalSide, boolean contingent, boolean allowNegativeBalance) {
    }
}
