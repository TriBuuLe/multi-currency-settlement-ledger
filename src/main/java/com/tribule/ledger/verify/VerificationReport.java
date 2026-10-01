package com.tribule.ledger.verify;

import com.tribule.ledger.ledger.TrialBalanceLine;

import java.time.Instant;
import java.util.List;

/**
 * The result of asking the ledger to prove itself.
 *
 * <p>Every invariant this system claims is also enforced by a database constraint,
 * so in principle this report can never find anything. That is exactly why it
 * exists: "cannot happen" is a hypothesis, and an unverified one tends to be wrong
 * after the third migration. A nightly run that recomputes the balances from the
 * journal and compares is the difference between believing the books and knowing.
 */
public record VerificationReport(
        Instant verifiedAt,
        boolean healthy,
        long transactions,
        long entries,
        long durationMillis,
        List<TrialBalanceLine> trialBalance,
        List<Finding> findings) {

    public VerificationReport {
        trialBalance = List.copyOf(trialBalance);
        findings = List.copyOf(findings);
    }

    public enum Severity {
        /** The books are wrong. Stop writing and investigate. */
        CRITICAL,
        /** Suspicious but not provably wrong. */
        WARNING
    }

    /**
     * One failed check.
     *
     * @param check   the invariant that did not hold
     * @param detail  what was observed
     * @param samples identifiers to start the investigation from
     */
    public record Finding(String check, Severity severity, String detail, List<String> samples) {

        public Finding {
            samples = samples == null ? List.of() : List.copyOf(samples);
        }

        static Finding critical(String check, String detail, List<String> samples) {
            return new Finding(check, Severity.CRITICAL, detail, samples);
        }

        static Finding warning(String check, String detail, List<String> samples) {
            return new Finding(check, Severity.WARNING, detail, samples);
        }
    }
}
