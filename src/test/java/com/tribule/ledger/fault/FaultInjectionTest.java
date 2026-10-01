package com.tribule.ledger.fault;

import com.tribule.ledger.ledger.ChartOfAccounts;
import com.tribule.ledger.ledger.LedgerException;
import com.tribule.ledger.ledger.PostingCommand;
import com.tribule.ledger.ledger.TransactionKind;
import com.tribule.ledger.settlement.AuthorizationService;
import com.tribule.ledger.settlement.SettlementException;
import com.tribule.ledger.settlement.SettlementService;
import com.tribule.ledger.support.AbstractLedgerTest;
import com.tribule.ledger.verify.LedgerVerificationService;
import com.tribule.ledger.verify.VerificationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the ledger looks like after things go wrong.
 *
 * <p>Correctness on the happy path is the easy half. The half that decides whether a
 * ledger can be trusted is what it contains after a request dies in the middle, two
 * requests collide, or a caller sends nonsense -- because those happen continuously in
 * production and each one is an opportunity to leave half a transaction behind.
 *
 * <p>The standard every test here holds the system to is the same: whatever was
 * attempted, the books still balance afterwards.
 */
class FaultInjectionTest extends AbstractLedgerTest {

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AuthorizationService authorizations;
    @Autowired private SettlementService settlements;
    @Autowired private LedgerVerificationService verification;

    @Test
    @DisplayName("a failure after the legs are written leaves nothing behind")
    void crashAfterPostingLeavesNothing() {
        String customer = newCustomer("crash");
        fund(customer, "USD", 10_000);
        long before = walletBalance(customer, "USD");
        UUID transactionId = UUID.randomUUID();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.execute(status -> {
            ledger.post(PostingCommand.of(TransactionKind.TRANSFER, Instant.now())
                    .id(transactionId)
                    .reference(newReference("doomed"))
                    .debit(ChartOfAccounts.wallet(customer, "USD"), 5_000, "about to fail")
                    .credit(ChartOfAccounts.nostro("USD"), 5_000, "about to fail")
                    .build());
            // The entries are written and the deferred constraints have already been
            // checked at this point. Then the process falls over.
            throw new IllegalStateException("simulated crash after posting");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM journal_transaction WHERE id = ?")
                .param(transactionId).query(Long.class).single())
                .as("a half-written transaction is not a thing that can exist")
                .isZero();
        assertThat(walletBalance(customer, "USD")).isEqualTo(before);
        assertBooksBalance();
    }

    @Test
    @DisplayName("the balance projection rolls back with the entries that produced it")
    void projectionRollsBackWithTheJournal() {
        String customer = newCustomer("rollback-projection");
        fund(customer, "EUR", 20_000);
        long before = walletBalance(customer, "EUR");

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.execute(status -> {
            transfers.payout(customer, "EUR", 15_000, newReference("rollback"), Instant.now(), UUID.randomUUID());
            // The trigger has already moved the projection by this point.
            throw new IllegalStateException("simulated crash after the projection updated");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(walletBalance(customer, "EUR"))
                .as("the projection is written in the same transaction, so it cannot survive alone")
                .isEqualTo(before);
        assertThat(balances.rebuild(ChartOfAccounts.wallet(customer, "EUR")).balanceMinor())
                .isEqualTo(balances.balanceOf(ChartOfAccounts.wallet(customer, "EUR")).signedBalanceMinor());
    }

    @Test
    @DisplayName("two settlements racing on one authorization pay the customer once")
    void concurrentSettlementPaysOnce() throws Exception {
        Instant t0 = nextTimeline();
        publishRate("EUR", "USD", "1.10", t0);
        String customer = newCustomer("race-settle");
        fund(customer, "EUR", 100_000);

        AuthorizationService.AuthorizationResult authorized = authorizations.authorize(
                new AuthorizationService.AuthorizeCommand(newReference("race"), customer, "EUR", "USD",
                        100_000, t0.plusSeconds(60), Duration.ofDays(30)),
                UUID.randomUUID());
        Instant settleAt = t0.plusSeconds(120);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            attempts.add(() -> {
                try {
                    settlements.settle(authorized.authorization().id(), settleAt, UUID.randomUUID());
                    succeeded.incrementAndGet();
                } catch (SettlementException e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        runAll(attempts);

        assertThat(succeeded.get())
                .as("exactly one settlement may win the version check")
                .isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(5);
        assertThat(walletBalance(customer, "USD"))
                .as("the losers' ledger postings roll back with their version check")
                .isEqualTo(110_000);
        assertThat(heldBalance(customer, "EUR")).isZero();
        assertBooksBalance();
    }

    @Test
    @DisplayName("rejected writes leave no trace in the journal")
    void rejectedWritesLeaveNoTrace() {
        String customer = newCustomer("rejected");
        fund(customer, "USD", 1_000);
        long entriesBefore = jdbc.sql("SELECT count(*) FROM journal_entry").query(Long.class).single();

        // Unbalanced.
        assertThatThrownBy(() -> ledger.post(PostingCommand.of(TransactionKind.TRANSFER, Instant.now())
                .debit(ChartOfAccounts.nostro("USD"), 100, "x")
                .credit(ChartOfAccounts.suspense("USD"), 99, "y")
                .build())).isInstanceOf(LedgerException.Unbalanced.class);

        // Overdrawn.
        assertThatThrownBy(() -> transfers.payout(customer, "USD", 999_999,
                newReference("over"), Instant.now(), UUID.randomUUID()))
                .isInstanceOf(LedgerException.InsufficientFunds.class);

        // A non-existent account.
        assertThatThrownBy(() -> ledger.post(PostingCommand.of(TransactionKind.TRANSFER, Instant.now())
                .debit("ASSET:NOSTRO:XXX", 100, "x")
                .credit(ChartOfAccounts.suspense("USD"), 100, "y")
                .build())).isInstanceOf(LedgerException.AccountNotFound.class);

        assertThat(jdbc.sql("SELECT count(*) FROM journal_entry").query(Long.class).single())
                .as("three rejected writes must add exactly zero entries")
                .isEqualTo(entriesBefore);
        assertThat(walletBalance(customer, "USD")).isEqualTo(1_000);
        assertBooksBalance();
    }

    @Test
    @DisplayName("a randomised workload with failures mixed in still verifies clean")
    void randomisedWorkloadWithFailuresStaysConsistent() {
        // A deterministic seed, so a failure here is reproducible rather than a ghost.
        Random random = new Random(20260930L);
        Instant t0 = nextTimeline();
        publishRate("EUR", "USD", "1.08", t0);

        List<String> customers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String customer = newCustomer("chaos-" + i);
            customers.add(customer);
            fund(customer, "USD", 50_000);
            fund(customer, "EUR", 50_000);
        }

        int attempted = 0;
        int failed = 0;
        for (int round = 0; round < 120; round++) {
            String from = customers.get(random.nextInt(customers.size()));
            String to = customers.get(random.nextInt(customers.size()));
            attempted++;
            try {
                switch (random.nextInt(5)) {
                    case 0 -> transfers.payout(from, "USD", random.nextInt(1, 30_000),
                            newReference("chaos"), Instant.now(), UUID.randomUUID());
                    case 1 -> {
                        if (from.equals(to)) {
                            throw new IllegalArgumentException("same customer, deliberately invalid");
                        }
                        transfers.transfer(from, to, "USD", random.nextInt(1, 20_000),
                                newReference("chaos"), Instant.now(), UUID.randomUUID());
                    }
                    // Deliberately oversized: these are supposed to be rejected.
                    case 2 -> transfers.payout(from, "EUR", random.nextInt(40_000, 200_000),
                            newReference("chaos"), Instant.now(), UUID.randomUUID());
                    case 3 -> transfers.convert(from, "EUR", "USD", random.nextInt(1, 10_000),
                            newReference("chaos"), Instant.now(), UUID.randomUUID());
                    default -> {
                        var authorization = authorizations.authorize(
                                new AuthorizationService.AuthorizeCommand(newReference("chaos"), from,
                                        "EUR", "USD", random.nextInt(1, 10_000),
                                        t0.plusSeconds(60), Duration.ofDays(30)),
                                UUID.randomUUID());
                        if (random.nextBoolean()) {
                            settlements.settle(authorization.authorization().id(),
                                    t0.plusSeconds(120), UUID.randomUUID());
                        } else {
                            authorizations.release(authorization.authorization().id(),
                                    "chaos release", t0.plusSeconds(120));
                        }
                    }
                }
            } catch (LedgerException | SettlementException | IllegalArgumentException e) {
                // Expected: roughly a fifth of these operations are designed to fail.
                failed++;
            }
        }

        assertThat(attempted).isEqualTo(120);
        assertThat(failed)
                .as("the workload is only interesting if a decent share of it was rejected")
                .isPositive();

        VerificationReport report = verification.verify();
        assertThat(report.findings())
                .as("after %d operations of which %d failed, the books must still add up",
                        attempted, failed)
                .isEmpty();
        assertThat(report.healthy()).isTrue();

        // And nobody ended up with a negative balance along the way.
        for (String customer : customers) {
            assertThat(walletBalance(customer, "USD")).isNotNegative();
            assertThat(walletBalance(customer, "EUR")).isNotNegative();
            assertThat(heldBalance(customer, "EUR")).isNotNegative();
        }
    }

    private void runAll(List<Callable<Void>> work) throws Exception {
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>(work.size());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Callable<Void> task : work) {
                futures.add(pool.submit(() -> {
                    startLine.await();
                    return task.call();
                }));
            }
            startLine.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        }
    }
}
