package com.tribule.ledger.recon;

import com.tribule.ledger.ledger.BalanceService;
import com.tribule.ledger.ledger.ChartOfAccounts;
import com.tribule.ledger.ledger.Direction;
import com.tribule.ledger.ledger.LedgerService;
import com.tribule.ledger.ledger.PostedTransaction;
import com.tribule.ledger.ledger.PostingCommand;
import com.tribule.ledger.ledger.TransactionKind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Reader;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Reconciles the ledger against an external statement.
 *
 * <p>The ledger is our opinion about what happened; the statement is the bank's.
 * Both are wrong sometimes and they are never in step, so the job is not to make
 * the numbers equal -- it is to account for every difference by name.
 *
 * <p>Matching is on external reference, then amount, then timing, and a statement
 * line can fail in five distinct ways (see {@link BreakType}). Three of those
 * failures carry enough information to act on without a human:
 *
 * <ul>
 *   <li><b>Duplicate line.</b> The bank sent one reference twice. Ignoring the
 *       second occurrence is the correct action and needs no judgement.
 *   <li><b>Timing difference.</b> Reference and amount agree; only the value date
 *       is outside tolerance. Nothing is wrong and nothing needs posting.
 *   <li><b>Missing in the ledger.</b> Money genuinely moved in our bank account
 *       and we do not know why. The books have to agree with the bank, so the
 *       movement is posted against a suspense account. The break closes -- and the
 *       suspense balance becomes the open item, which is the honest place for it.
 * </ul>
 *
 * <p>The other two are left open deliberately. An amount mismatch and a payment
 * the bank has not reported both need someone to decide what is true, and a matcher
 * that guessed would be manufacturing entries to make a report look clean.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRepository repository;
    private final StatementCsvParser parser;
    private final LedgerService ledger;
    private final BalanceService balances;
    private final Duration timingTolerance;
    private final Duration ledgerWindow;
    private final Timer reconcileTimer;

    public ReconciliationService(ReconciliationRepository repository,
                                StatementCsvParser parser,
                                LedgerService ledger,
                                BalanceService balances,
                                @Value("${ledger.recon.timing-tolerance:PT48H}") Duration timingTolerance,
                                @Value("${ledger.recon.ledger-window:PT168H}") Duration ledgerWindow,
                                MeterRegistry registry) {
        this.repository = repository;
        this.parser = parser;
        this.ledger = ledger;
        this.balances = balances;
        this.timingTolerance = timingTolerance;
        this.ledgerWindow = ledgerWindow;
        this.reconcileTimer = Timer.builder("ledger.reconciliation.duration")
                .description("Time to reconcile one statement batch")
                .register(registry);
    }

    /**
     * Imports a statement file.
     *
     * <p>Separate from reconciling it, and idempotent on (source, filename) by a
     * unique constraint: re-uploading the same file is a common operational
     * accident and it must not be able to double-count anything.
     */
    @Transactional
    public StatementBatch importStatement(String source, String filename, LocalDate asOfDate, Reader content) {
        List<StatementCsvParser.ParsedLine> lines = parser.parse(content);
        StatementBatch batch = repository.insertBatch(source, filename, asOfDate, lines.size());
        repository.insertLines(batch.id(), lines);
        log.info("imported {} line(s) from {}/{}", lines.size(), source, filename);
        return batch;
    }

    @Transactional
    public ReconciliationReport reconcile(UUID batchId) {
        return reconcileTimer.record(() -> doReconcile(batchId));
    }

    private ReconciliationReport doReconcile(UUID batchId) {
        StatementBatch batch = repository.findBatch(batchId)
                .orElseThrow(() -> new ReconciliationException.BatchNotFound(batchId));
        List<StatementLine> lines = repository.findLines(batchId);
        Instant now = Instant.now();

        List<ReconciliationBreak> breaks = new ArrayList<>();
        List<UUID> adjustments = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> currencies = new LinkedHashSet<>();
        Set<String> seenRefs = new HashSet<>();
        Map<String, Integer> refCounts = new HashMap<>();
        int matched = 0;

        for (StatementLine line : lines) {
            refCounts.merge(line.externalRef(), 1, Integer::sum);
        }

        for (StatementLine line : lines) {
            currencies.add(line.currencyCode());
            String nostro = ChartOfAccounts.nostro(line.currencyCode());

            // A reference we have already processed in this file is a duplicate,
            // whatever it says: acting on it twice would double-count real money.
            if (!seenRefs.add(line.externalRef())) {
                breaks.add(record(new ReconciliationBreak(null, batchId, line.id(), null,
                        BreakType.DUPLICATE_IN_STATEMENT, line.currencyCode(), 0,
                        BreakStatus.AUTO_RESOLVED,
                        "ignored: reference appears %d times in this file; the first occurrence was used"
                                .formatted(refCounts.get(line.externalRef())),
                        null, null, now)));
                repository.markLine(line.id(), "BROKEN", null);
                continue;
            }

            Optional<ReconciliationRepository.LedgerMovement> found =
                    repository.findLedgerMovement(line.externalRef(), nostro);

            if (found.isEmpty()) {
                // The bank moved money we never booked. The books must agree with
                // the bank, so post it to suspense and leave the identification to
                // whoever owns the suspense balance.
                PostedTransaction adjustment = postSuspenseAdjustment(line, now);
                adjustments.add(adjustment.transaction().id());
                breaks.add(record(new ReconciliationBreak(null, batchId, line.id(), null,
                        BreakType.MISSING_IN_LEDGER, line.currencyCode(), -line.signedAmountMinor(),
                        BreakStatus.AUTO_RESOLVED,
                        "booked to %s pending identification".formatted(ChartOfAccounts.suspense(line.currencyCode())),
                        adjustment.transaction().id(), null, now)));
                repository.markLine(line.id(), "BROKEN", adjustment.transaction().id());
                continue;
            }

            ReconciliationRepository.LedgerMovement movement = found.get();
            long delta = movement.signedAmountMinor() - line.signedAmountMinor();

            if (delta != 0) {
                // Somebody has to decide which side is right. Posting a plug here
                // would make the report look clean and the books wrong.
                breaks.add(record(new ReconciliationBreak(null, batchId, line.id(), movement.transactionId(),
                        BreakType.AMOUNT_MISMATCH, line.currencyCode(), delta,
                        BreakStatus.OPEN,
                        "ledger %d, statement %d (ledger is out by %d minor units)"
                                .formatted(movement.signedAmountMinor(), line.signedAmountMinor(), delta),
                        null, null, null)));
                repository.markLine(line.id(), "BROKEN", movement.transactionId());
                continue;
            }

            Duration drift = Duration.between(movement.occurredAt(), line.postedAt()).abs();
            if (drift.compareTo(timingTolerance) > 0) {
                breaks.add(record(new ReconciliationBreak(null, batchId, line.id(), movement.transactionId(),
                        BreakType.TIMING_DIFFERENCE, line.currencyCode(), 0,
                        BreakStatus.AUTO_RESOLVED,
                        "amount agrees; bank value date is %d hour(s) from ours, tolerance is %d"
                                .formatted(drift.toHours(), timingTolerance.toHours()),
                        null, null, now)));
                repository.markLine(line.id(), "MATCHED", movement.transactionId());
                matched++;
                continue;
            }

            repository.markLine(line.id(), "MATCHED", movement.transactionId());
            matched++;
        }

        // The other direction: things we booked that the bank has not reported.
        Instant windowEnd = batch.asOfDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant windowStart = windowEnd.minus(ledgerWindow);
        for (String currency : currencies) {
            for (ReconciliationRepository.LedgerMovement movement
                    : repository.findLedgerMovements(ChartOfAccounts.nostro(currency), windowStart, windowEnd)) {
                if (seenRefs.contains(movement.reference())) {
                    continue;
                }
                breaks.add(record(new ReconciliationBreak(null, batchId, null, movement.transactionId(),
                        BreakType.MISSING_IN_STATEMENT, movement.currencyCode(), movement.signedAmountMinor(),
                        BreakStatus.OPEN,
                        "booked %s on %s; not present in this statement (may still be in flight)"
                                .formatted(movement.reference(), movement.occurredAt()),
                        null, null, null)));
            }
        }
        notes.add("ledger side compared over the %d hour(s) before %s".formatted(ledgerWindow.toHours(), windowEnd));

        Map<String, Integer> byType = new TreeMap<>();
        int autoResolved = 0;
        List<ReconciliationBreak> open = new ArrayList<>();
        for (ReconciliationBreak reconciliationBreak : breaks) {
            byType.merge(reconciliationBreak.breakType().name(), 1, Integer::sum);
            if (reconciliationBreak.status() == BreakStatus.AUTO_RESOLVED) {
                autoResolved++;
            } else {
                open.add(reconciliationBreak);
            }
        }

        Map<String, Long> suspense = new LinkedHashMap<>();
        for (String currency : currencies) {
            suspense.put(currency, balances.balanceOf(ChartOfAccounts.suspense(currency)).normalBalanceMinor());
        }
        if (suspense.values().stream().anyMatch(v -> v != 0)) {
            notes.add("suspense is not flat: those balances are the real open items from auto-resolution");
        }

        double rate = breaks.isEmpty() ? 1.0 : (double) autoResolved / breaks.size();
        log.info("reconciled {}: {} line(s), {} matched, {} break(s), {} auto-resolved ({}%)",
                batch.filename(), lines.size(), matched, breaks.size(), autoResolved, Math.round(rate * 100));

        return new ReconciliationReport(batchId, batch.source(), batch.filename(), now,
                lines.size(), matched, breaks.size(), autoResolved, open.size(), rate,
                byType, open, adjustments, suspense, notes);
    }

    /**
     * Brings the ledger into line with a bank movement we cannot explain yet.
     *
     * <p>The nostro leg mirrors the bank exactly; the other leg goes to suspense.
     * Nothing is invented -- the entry says "this much money moved and we do not
     * know why", which is true, and it stays visible until someone reclassifies it
     * with a normal transfer.
     */
    private PostedTransaction postSuspenseAdjustment(StatementLine line, Instant now) {
        String nostro = ChartOfAccounts.nostro(line.currencyCode());
        String suspense = ChartOfAccounts.suspense(line.currencyCode());
        boolean moneyIn = line.direction() == Direction.DEBIT;

        PostingCommand.Builder builder = PostingCommand.of(TransactionKind.RECON_ADJUSTMENT, line.postedAt())
                .reference(line.externalRef())
                .description("unidentified statement movement: " + line.description())
                .correlationId(line.externalRef());

        if (moneyIn) {
            builder.debit(nostro, line.amountMinor(), "per statement " + line.externalRef())
                    .credit(suspense, line.amountMinor(), "unidentified credit");
        } else {
            builder.credit(nostro, line.amountMinor(), "per statement " + line.externalRef())
                    .debit(suspense, line.amountMinor(), "unidentified debit");
        }
        return ledger.post(builder.build());
    }

    private ReconciliationBreak record(ReconciliationBreak reconciliationBreak) {
        return repository.insertBreak(reconciliationBreak);
    }

    @Transactional(readOnly = true)
    public List<ReconciliationBreak> breaksFor(UUID batchId) {
        return repository.findBreaks(batchId);
    }

    @Transactional(readOnly = true)
    public int openBreakCount() {
        return repository.countOpenBreaks();
    }
}
