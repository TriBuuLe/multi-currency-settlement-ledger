package com.tribule.ledger.verify;

import com.tribule.ledger.ledger.AccountBalance;
import com.tribule.ledger.ledger.BalanceRepository;
import com.tribule.ledger.ledger.ChartOfAccounts;
import com.tribule.ledger.settlement.AuthorizationService;
import com.tribule.ledger.settlement.SettlementService;
import com.tribule.ledger.support.AbstractLedgerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asking the ledger to prove itself.
 *
 * <p>Every invariant here is also enforced by a database constraint, so in principle
 * the verifier can never find anything. That is exactly why it is worth having: "cannot
 * happen" is a hypothesis, and an unverified hypothesis tends to be wrong a few
 * migrations later. These tests check both that a real workload passes, and that a
 * deliberately corrupted projection is actually caught rather than quietly tolerated.
 */
class LedgerVerificationTest extends AbstractLedgerTest {

    @Autowired private LedgerVerificationService verification;
    @Autowired private BalanceRepository balanceRepository;
    @Autowired private AuthorizationService authorizations;
    @Autowired private SettlementService settlements;

    @Test
    @DisplayName("a mixed workload leaves the books verifiably correct")
    void realisticWorkloadVerifiesClean() {
        Instant t0 = nextTimeline();
        Instant t1 = t0.plus(Duration.ofDays(1));
        publishRate("EUR", "USD", "1.09", t0);
        publishRate("JPY", "USD", "0.0064", t0);
        publishRate("USD", "KWD", "0.3071", t0);

        String alice = newCustomer("verify-alice");
        String bob = newCustomer("verify-bob");

        fund(alice, "EUR", 500_000);
        fund(bob, "EUR", 250_000);
        transfers.transfer(alice, bob, "EUR", 50_000, newReference("v-transfer"), t0, UUID.randomUUID());
        transfers.payout(bob, "EUR", 25_000, newReference("v-payout"), t0, UUID.randomUUID());

        // A triangulated conversion, so the rounding account is exercised too.
        fund(alice, "JPY", 1_000_000);
        transfers.convert(alice, "JPY", "KWD", 123_457, newReference("v-convert"),
                t0.plusSeconds(30), UUID.randomUUID());

        // An authorization that settles, and one that stays open.
        AuthorizationService.AuthorizationResult settledAuth = authorizations.authorize(
                new AuthorizationService.AuthorizeCommand(newReference("v-settle"), alice, "EUR", "USD",
                        100_000, t0.plusSeconds(60), Duration.ofDays(30)),
                UUID.randomUUID());
        publishRate("EUR", "USD", "1.12", t1);
        settlements.settle(settledAuth.authorization().id(), t1.plusSeconds(60), UUID.randomUUID());

        authorizations.authorize(new AuthorizationService.AuthorizeCommand(
                        newReference("v-open"), bob, "EUR", "USD", 75_000,
                        t1.plusSeconds(120), Duration.ofDays(30)),
                UUID.randomUUID());

        VerificationReport report = verification.verify();

        assertThat(report.findings())
                .as("a clean workload must produce no findings at all")
                .isEmpty();
        assertThat(report.healthy()).isTrue();
        assertThat(report.transactions()).isPositive();
        assertThat(report.entries()).isPositive();
        assertThat(report.trialBalance()).isNotEmpty().allMatch(line -> line.isBalanced());
    }

    @Test
    @DisplayName("the cross-domain check ties open authorizations to the accounts backing them")
    void holdsAndCommitmentsBackOpenAuthorizations() {
        Instant t0 = nextTimeline();
        publishRate("GBP", "USD", "1.26", t0);

        String customer = newCustomer("backing");
        fund(customer, "GBP", 90_000);
        authorizations.authorize(new AuthorizationService.AuthorizeCommand(
                        newReference("backing"), customer, "GBP", "USD", 90_000,
                        t0.plusSeconds(60), Duration.ofDays(30)),
                UUID.randomUUID());

        // Each subsystem is self-consistent on its own. This is the only check that
        // would catch the authorization table and the ledger drifting apart.
        VerificationReport report = verification.verify();

        assertThat(report.findings())
                .noneMatch(f -> f.check().equals("holds-back-open-authorizations"))
                .noneMatch(f -> f.check().equals("commitments-match-open-authorizations"));
        assertThat(heldBalance(customer, "GBP")).isEqualTo(90_000);
    }

    @Test
    @DisplayName("a corrupted balance projection is caught, named, and does not pass as healthy")
    void corruptedProjectionIsDetected() {
        // The projection is maintained by a trigger, so this cannot happen through the
        // service. It is forced here precisely to prove the verifier is not vacuous:
        // a check that has never failed in a test is a check nobody knows works.
        String customer = newCustomer("corrupt");
        fund(customer, "USD", 12_345);
        String walletCode = ChartOfAccounts.wallet(customer, "USD");
        AccountBalance before = balances.balanceOf(walletCode);

        try {
            jdbc.sql("UPDATE account_balance SET balance_minor = balance_minor + 1 WHERE account_id = ?")
                    .param(before.accountId())
                    .update();

            VerificationReport report = verification.verify();

            assertThat(report.healthy()).isFalse();
            assertThat(report.findings())
                    .anyMatch(f -> f.check().equals("projection-matches-journal"))
                    .anyMatch(f -> f.severity() == VerificationReport.Severity.CRITICAL);
            // The trial balance no longer sums to zero either, which is the headline symptom.
            assertThat(report.trialBalance()).anyMatch(line -> !line.isBalanced());
        } finally {
            jdbc.sql("UPDATE account_balance SET balance_minor = ? WHERE account_id = ?")
                    .params(before.signedBalanceMinor(), before.accountId())
                    .update();
        }

        assertThat(verification.verify().healthy())
                .as("and healthy again once the projection is put back")
                .isTrue();
    }

    @Test
    @DisplayName("rebuilding from the journal reproduces the projection, and snapshots bound the work")
    void rebuildReproducesProjectionAndSnapshotsBoundIt() {
        String customer = newCustomer("rebuild");
        fund(customer, "USD", 100_000);
        for (int i = 0; i < 10; i++) {
            transfers.payout(customer, "USD", 1_000, newReference("rb"), Instant.now(), UUID.randomUUID());
        }
        String walletCode = ChartOfAccounts.wallet(customer, "USD");

        AccountBalance projected = balances.balanceOf(walletCode);
        BalanceRepository.RebuiltBalance fromScratch = balances.rebuild(walletCode);

        assertThat(fromScratch.balanceMinor()).isEqualTo(projected.signedBalanceMinor());
        assertThat(fromScratch.snapshotEntryId()).isZero();
        assertThat(fromScratch.entriesReplayed()).isEqualTo(11);

        // Checkpoint, then post more. The replay now starts from the snapshot instead
        // of from the beginning of the account's history.
        balances.snapshot(walletCode);
        transfers.payout(customer, "USD", 500, newReference("rb-after"), Instant.now(), UUID.randomUUID());

        BalanceRepository.RebuiltBalance fromSnapshot = balances.rebuild(walletCode);

        assertThat(fromSnapshot.balanceMinor()).isEqualTo(balances.balanceOf(walletCode).signedBalanceMinor());
        assertThat(fromSnapshot.snapshotEntryId()).isPositive();
        assertThat(fromSnapshot.entriesReplayed())
                .as("only the entries written since the checkpoint need replaying")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the verifier recomputes the trial balance from the journal, not just from the projection")
    void journalAndProjectionAreComparedIndependently() {
        fund(newCustomer("independent"), "CHF", 4_200);

        // Both must be zero. Computing only one would let a corrupted projection hide a
        // corrupted journal, or the reverse.
        assertThat(balanceRepository.trialBalance()).allMatch(line -> line.isBalanced());
        assertThat(balanceRepository.trialBalanceFromJournal()).allMatch(line -> line.isBalanced());
        assertThat(balanceRepository.findUnbalancedTransactions(10)).isEmpty();
        assertThat(balanceRepository.findDriftedAccounts(10)).isEmpty();
        assertThat(balanceRepository.findOverdrawnAccounts(10)).isEmpty();
    }
}
