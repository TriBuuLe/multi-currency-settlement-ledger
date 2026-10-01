package com.tribule.ledger.verify;

import com.tribule.ledger.ledger.BalanceRepository;
import com.tribule.ledger.ledger.ChartOfAccounts;
import com.tribule.ledger.ledger.JournalRepository;
import com.tribule.ledger.ledger.TrialBalanceLine;
import com.tribule.ledger.settlement.AuthorizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Recomputes everything the ledger asserts about itself and reports what fails.
 *
 * <p>Six checks, in rough order of how badly you want them to pass:
 *
 * <ol>
 *   <li><b>The projection's trial balance is zero in every currency.</b> The
 *       headline invariant, cheap to check.
 *   <li><b>The journal's trial balance is zero in every currency.</b> Same claim
 *       recomputed from the entries, so a corrupted projection cannot hide a
 *       corrupted journal or the reverse.
 *   <li><b>Every account's projection equals a fresh replay of its entries.</b>
 *       Per-account, which turns "something is wrong" into "this account is wrong".
 *   <li><b>Every single transaction balances per currency.</b> Transaction-level,
 *       because two equal and opposite errors would pass a global check.
 *   <li><b>No account that forbids a negative balance has one.</b>
 *   <li><b>Open authorizations agree with the accounts that back them.</b> The
 *       cross-domain check: held customer funds must equal the sell notional of
 *       open authorizations, and contingent commitments must equal their quoted buy
 *       notional. Each subsystem is self-consistent on its own; this is the only
 *       check that catches the two drifting apart.
 * </ol>
 */
@Service
public class LedgerVerificationService {

    private static final Logger log = LoggerFactory.getLogger(LedgerVerificationService.class);
    private static final int SAMPLE_LIMIT = 20;

    private final BalanceRepository balances;
    private final JournalRepository journal;
    private final AuthorizationRepository authorizations;

    public LedgerVerificationService(BalanceRepository balances,
                                    JournalRepository journal,
                                    AuthorizationRepository authorizations) {
        this.balances = balances;
        this.journal = journal;
        this.authorizations = authorizations;
    }

    @Transactional(readOnly = true)
    public VerificationReport verify() {
        long startedAt = System.nanoTime();
        List<VerificationReport.Finding> findings = new ArrayList<>();

        List<TrialBalanceLine> projected = balances.trialBalance();
        for (TrialBalanceLine line : projected) {
            if (!line.isBalanced()) {
                findings.add(VerificationReport.Finding.critical(
                        "projection-trial-balance",
                        "%s does not sum to zero: residual %d minor units across %d account(s)"
                                .formatted(line.currencyCode(), line.residualMinor(), line.accountCount()),
                        List.of(line.currencyCode())));
            }
        }

        for (TrialBalanceLine line : balances.trialBalanceFromJournal()) {
            if (!line.isBalanced()) {
                findings.add(VerificationReport.Finding.critical(
                        "journal-trial-balance",
                        "%s does not sum to zero when recomputed from the journal: residual %d minor units"
                                .formatted(line.currencyCode(), line.residualMinor()),
                        List.of(line.currencyCode())));
            }
        }

        List<UUID> drifted = balances.findDriftedAccounts(SAMPLE_LIMIT);
        if (!drifted.isEmpty()) {
            findings.add(VerificationReport.Finding.critical(
                    "projection-matches-journal",
                    "%d account(s) have a projected balance that disagrees with their entries".formatted(drifted.size()),
                    drifted.stream().map(UUID::toString).toList()));
        }

        List<UUID> unbalanced = balances.findUnbalancedTransactions(SAMPLE_LIMIT);
        if (!unbalanced.isEmpty()) {
            findings.add(VerificationReport.Finding.critical(
                    "every-transaction-balances",
                    "%d transaction(s) do not balance in at least one currency".formatted(unbalanced.size()),
                    unbalanced.stream().map(UUID::toString).toList()));
        }

        List<String> overdrawn = balances.findOverdrawnAccounts(SAMPLE_LIMIT);
        if (!overdrawn.isEmpty()) {
            findings.add(VerificationReport.Finding.critical(
                    "no-overdrawn-accounts",
                    "%d account(s) that forbid a negative balance are negative".formatted(overdrawn.size()),
                    overdrawn));
        }

        findings.addAll(verifyAuthorizationBacking());

        long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
        boolean healthy = findings.stream().noneMatch(f -> f.severity() == VerificationReport.Severity.CRITICAL);
        if (!healthy) {
            log.error("ledger verification failed with {} finding(s): {}", findings.size(), findings);
        }

        return new VerificationReport(Instant.now(), healthy,
                journal.countTransactions(), journal.countEntries(), durationMillis, projected, findings);
    }

    /**
     * Checks that the authorization table and the accounts backing it tell the same
     * story.
     *
     * <p>Held funds should equal the sell notional of open authorizations, and
     * contingent commitments should equal their quoted buy notional. Both sides are
     * written in one transaction, so they can only diverge if a code path updates
     * one without the other -- which is precisely the bug nobody notices until a
     * customer's money is stuck in a hold for an authorization that no longer
     * exists.
     */
    private List<VerificationReport.Finding> verifyAuthorizationBacking() {
        List<VerificationReport.Finding> findings = new ArrayList<>();

        Map<String, Long> heldInAccounts = balances.sumNormalBalanceByCodePrefix("LIABILITY:CUSTOMER_HOLD:");
        Map<String, Long> pendingSell = authorizations.pendingSellByCurrency();
        Set<String> sellCurrencies = new LinkedHashSet<>(heldInAccounts.keySet());
        sellCurrencies.addAll(pendingSell.keySet());

        for (String currency : sellCurrencies) {
            long held = heldInAccounts.getOrDefault(currency, 0L);
            long expected = pendingSell.getOrDefault(currency, 0L);
            if (held != expected) {
                findings.add(VerificationReport.Finding.critical(
                        "holds-back-open-authorizations",
                        "%s: hold accounts total %d but open authorizations need %d (out by %d)"
                                .formatted(currency, held, expected, held - expected),
                        List.of(currency)));
            }
        }

        Map<String, Long> pendingBuy = authorizations.pendingQuotedByBuyCurrency();
        Set<String> buyCurrencies = new LinkedHashSet<>(pendingBuy.keySet());
        for (String currency : buyCurrencies) {
            long commitment = balances.findByCode(ChartOfAccounts.fxCommitment(currency))
                    .map(b -> b.normalBalanceMinor())
                    .orElse(0L);
            long expected = pendingBuy.getOrDefault(currency, 0L);
            if (commitment != expected) {
                findings.add(VerificationReport.Finding.critical(
                        "commitments-match-open-authorizations",
                        "%s: contingent commitments total %d but open authorizations promise %d (out by %d)"
                                .formatted(currency, commitment, expected, commitment - expected),
                        List.of(currency)));
            }
        }
        return findings;
    }
}
