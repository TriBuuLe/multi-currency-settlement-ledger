package com.tribule.ledger.web;

import com.tribule.ledger.fx.FxRate;
import com.tribule.ledger.fx.ResolvedRate;
import com.tribule.ledger.ledger.Account;
import com.tribule.ledger.ledger.AccountBalance;
import com.tribule.ledger.ledger.BalanceRepository;
import com.tribule.ledger.ledger.CurrencyRepository;
import com.tribule.ledger.ledger.JournalEntry;
import com.tribule.ledger.ledger.PostedTransaction;
import com.tribule.ledger.ledger.TrialBalanceLine;
import com.tribule.ledger.money.CurrencyUnit;
import com.tribule.ledger.money.Money;
import com.tribule.ledger.recon.ReconciliationBreak;
import com.tribule.ledger.recon.ReconciliationReport;
import com.tribule.ledger.recon.StatementBatch;
import com.tribule.ledger.risk.CurrencyExposure;
import com.tribule.ledger.risk.ExposureReport;
import com.tribule.ledger.risk.OpenPosition;
import com.tribule.ledger.risk.VarEstimate;
import com.tribule.ledger.settlement.AuthorizationService;
import com.tribule.ledger.settlement.FxAuthorization;
import com.tribule.ledger.settlement.RateCorrectionReplayService;
import com.tribule.ledger.settlement.SettlementService;
import com.tribule.ledger.verify.VerificationReport;
import com.tribule.ledger.web.dto.Responses;
import org.springframework.stereotype.Component;

import java.util.List;

/** Turns domain objects into the wire shape. Kept in one place so the API surface is reviewable. */
@Component
public class ViewMapper {

    private final CurrencyRepository currencies;

    public ViewMapper(CurrencyRepository currencies) {
        this.currencies = currencies;
    }

    public Responses.MoneyView money(Money money) {
        return new Responses.MoneyView(money.minorUnits(), money.currency().code(), money.toString());
    }

    public Responses.MoneyView money(long minorUnits, String currencyCode) {
        return money(Money.ofMinor(minorUnits, currencies.require(currencyCode)));
    }

    public Responses.TransactionView transaction(PostedTransaction posted) {
        return new Responses.TransactionView(
                posted.transaction().id(),
                posted.transaction().kind().name(),
                posted.transaction().reference(),
                posted.transaction().description(),
                posted.transaction().occurredAt(),
                posted.transaction().recordedAt(),
                posted.transaction().reversesTransactionId(),
                posted.transaction().fxRateId(),
                posted.entries().stream().map(this::entry).toList());
    }

    public Responses.EntryView entry(JournalEntry entry) {
        return new Responses.EntryView(entry.id(), entry.accountCode(), entry.currencyCode(),
                entry.direction().name(), entry.amountMinor(), entry.memo());
    }

    public Responses.RateView rate(ResolvedRate resolved) {
        return new Responses.RateView(resolved.pair(), resolved.rate(), resolved.resolution().name(),
                resolved.primaryRateId(), resolved.pivotCurrency(),
                resolved.firstLegRate(), resolved.secondLegRate());
    }

    public Responses.FxRateView fxRate(FxRate rate) {
        return new Responses.FxRateView(rate.id(), rate.pair(), rate.rate(),
                rate.effectiveAt(), rate.observedAt(), rate.source(), rate.supersedesId());
    }

    public Responses.AccountView account(Account account) {
        return new Responses.AccountView(account.id(), account.code(), account.name(),
                account.currency().code(), account.type().name(), account.normalSide().name(),
                account.contingent(), account.allowNegativeBalance());
    }

    public Responses.BalanceView balance(AccountBalance balance) {
        CurrencyUnit currency = currencies.require(balance.currencyCode());
        return new Responses.BalanceView(
                balance.accountCode(),
                balance.currencyCode(),
                balance.signedBalanceMinor(),
                balance.normalBalanceMinor(),
                Money.ofMinor(balance.normalBalanceMinor(), currency).toString(),
                balance.entryCount(),
                balance.lastEntryId(),
                balance.version(),
                balance.updatedAt());
    }

    public Responses.TrialBalanceView trialBalance(TrialBalanceLine line) {
        return new Responses.TrialBalanceView(line.currencyCode(), line.totalDebitsMinor(),
                line.totalCreditsMinor(), line.residualMinor(), line.accountCount(), line.isBalanced());
    }

    public Responses.RebuildView rebuild(String accountCode, long projectedMinor,
                                         BalanceRepository.RebuiltBalance rebuilt) {
        return new Responses.RebuildView(accountCode, projectedMinor, rebuilt.balanceMinor(),
                projectedMinor == rebuilt.balanceMinor(), rebuilt.entriesReplayed(), rebuilt.snapshotEntryId());
    }

    public Responses.VerificationView verification(VerificationReport report) {
        return new Responses.VerificationView(
                report.verifiedAt(), report.healthy(), report.transactions(), report.entries(),
                report.durationMillis(),
                report.trialBalance().stream().map(this::trialBalance).toList(),
                report.findings().stream()
                        .map(f -> new Responses.FindingView(f.check(), f.severity().name(), f.detail(), f.samples()))
                        .toList());
    }

    public Responses.AuthorizationView authorization(FxAuthorization authorization) {
        return new Responses.AuthorizationView(
                authorization.id(),
                authorization.reference(),
                authorization.customerId(),
                authorization.pair(),
                money(authorization.sellAmountMinor(), authorization.sellCurrency()),
                money(authorization.quotedBuyAmountMinor(), authorization.buyCurrency()),
                authorization.quotedRate(),
                authorization.quotedRateId(),
                authorization.status().name(),
                authorization.authorizedAt(),
                authorization.expiresAt(),
                authorization.settledAt(),
                authorization.holdTransactionId(),
                authorization.settlementTransactionId(),
                authorization.settlementRate(),
                authorization.realizedPnlMinor() == null
                        ? null
                        : money(authorization.realizedPnlMinor(), authorization.realizedPnlCurrency()));
    }

    public Responses.AuthorizationCreatedView authorizationCreated(AuthorizationService.AuthorizationResult result) {
        return new Responses.AuthorizationCreatedView(
                authorization(result.authorization()),
                transaction(result.holdTransaction()),
                rate(result.quotedRate()));
    }

    public Responses.SettlementView settlement(SettlementService.SettlementResult result) {
        long pnl = result.realizedPnl().minorUnits();
        String explanation = pnl == 0
                ? "the rate did not move between authorization and settlement"
                : "the market delivered %s against %s promised, so the house %s %s"
                        .formatted(result.marketValue(), result.delivered(),
                                pnl > 0 ? "gained" : "absorbed", result.realizedPnl().abs());
        return new Responses.SettlementView(
                authorization(result.authorization()),
                transaction(result.settlementTransaction()),
                rate(result.settlementRate()),
                money(result.delivered()),
                money(result.marketValue()),
                money(result.realizedPnl()),
                money(result.roundingResidual()),
                explanation);
    }

    public Responses.ExposureView exposure(ExposureReport report) {
        return new Responses.ExposureView(
                report.asOf(),
                report.reportingCurrency(),
                report.openAuthorizationCount(),
                report.totalUnrealizedPnlReportingMinor(),
                report.grossExposureReportingMinor(),
                report.portfolioVarReportingMinor(),
                report.positions().stream().map(this::position).toList(),
                report.netByCurrency().stream().map(this::exposureLine).toList(),
                report.valueAtRisk().stream().map(this::var).toList(),
                report.notes());
    }

    private Responses.OpenPositionView position(OpenPosition position) {
        return new Responses.OpenPositionView(
                position.pair(),
                position.authorizationCount(),
                money(position.sellNotionalMinor(), position.sellCurrency()),
                money(position.quotedBuyNotionalMinor(), position.buyCurrency()),
                money(position.markedBuyNotionalMinor(), position.buyCurrency()),
                money(position.unrealizedPnlMinor(), position.buyCurrency()),
                position.unrealizedPnlReportingMinor(),
                position.weightedQuotedRate(),
                position.currentRate(),
                position.oldestAgeSeconds());
    }

    private Responses.CurrencyExposureView exposureLine(CurrencyExposure exposure) {
        return new Responses.CurrencyExposureView(
                exposure.currency(),
                exposure.netPositionMinor(),
                exposure.netPositionReportingMinor(),
                exposure.netPositionMinor() == 0 ? "FLAT" : (exposure.isShort() ? "SHORT" : "LONG"),
                exposure.authorizationCount());
    }

    private Responses.VarView var(VarEstimate estimate) {
        return new Responses.VarView(estimate.pair(), estimate.observations(), estimate.confidence(),
                estimate.horizonDays(), estimate.worstReturn(), estimate.varReportingMinor(),
                estimate.sufficientData(), estimate.method());
    }

    public Responses.StatementBatchView batch(StatementBatch batch) {
        return new Responses.StatementBatchView(batch.id(), batch.source(), batch.filename(),
                batch.asOfDate().toString(), batch.lineCount(), batch.importedAt());
    }

    public Responses.ReconciliationView reconciliation(ReconciliationReport report) {
        return new Responses.ReconciliationView(
                report.batchId(), report.source(), report.filename(), report.reconciledAt(),
                report.statementLines(), report.matched(), report.breaksDetected(), report.autoResolved(),
                report.open(), report.autoResolutionRate(), report.breaksByType(),
                report.openBreaks().stream().map(this::breakView).toList(),
                report.adjustmentTransactions(), report.suspenseBalancesMinor(), report.notes());
    }

    public Responses.BreakView breakView(ReconciliationBreak item) {
        return new Responses.BreakView(item.id(), item.breakType().name(), item.currencyCode(),
                item.deltaMinor(), item.status().name(), item.resolution(), item.statementLineId(),
                item.transactionId(), item.adjustmentTransactionId(), item.detectedAt());
    }

    public List<Responses.BreakView> breaks(List<ReconciliationBreak> items) {
        return items.stream().map(this::breakView).toList();
    }

    public Responses.CorrectionReplayView replay(RateCorrectionReplayService.ReplayResult result) {
        return new Responses.CorrectionReplayView(
                result.correctionRateId(), result.supersededRateId(), result.pair(), result.effectiveAt(),
                result.settlementsExamined(), result.adjustmentsPosted(), result.netPnlDeltaMinor(),
                result.adjustments().stream()
                        .map(a -> new Responses.AdjustmentView(a.authorizationId(), a.reference(),
                                a.originalPnlMinor(), a.correctedPnlMinor(), a.pnlDeltaMinor(),
                                a.currency(), a.adjustmentTransactionId()))
                        .toList(),
                result.notes());
    }
}
