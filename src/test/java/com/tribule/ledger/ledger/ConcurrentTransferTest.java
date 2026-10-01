package com.tribule.ledger.ledger;

import com.tribule.ledger.support.AbstractLedgerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when many requests hit the same account at once.
 *
 * <p>This is where ledgers actually break. The dangerous bug is not a crash -- it is
 * two concurrent withdrawals both reading a balance of 100, both deciding 100 is
 * enough, and both succeeding. A check-then-write in application code has exactly
 * that race, and it is invisible in single-threaded tests.
 *
 * <p>The design that avoids it: the balance projection is maintained by an UPSERT on
 * one row per account, so concurrent writers to that account contend on a row lock
 * and serialise. The second transaction cannot see a stale balance because it cannot
 * proceed until the first commits. That is why these tests pass under plain READ
 * COMMITTED, with no application-level locking anywhere.
 */
class ConcurrentTransferTest extends AbstractLedgerTest {

    @Autowired
    private TransactionalRetry retry;

    @Test
    @DisplayName("concurrent withdrawals from one wallet conserve money exactly")
    void concurrentWithdrawalsConserveMoney() throws Exception {
        String customer = newCustomer("hot-account");
        int threads = 32;
        long each = 100;
        fund(customer, "USD", threads * each);

        AtomicInteger succeeded = new AtomicInteger();
        runConcurrently(threads, () -> {
            retry.execute("payout", () -> transfers.payout(customer, "USD", each,
                    newReference("concurrent"), Instant.now(), UUID.randomUUID()));
            succeeded.incrementAndGet();
            return null;
        });

        assertThat(succeeded.get()).isEqualTo(threads);
        assertThat(walletBalance(customer, "USD"))
                .as("every withdrawal landed exactly once")
                .isZero();
        assertBooksBalance();
    }

    @Test
    @DisplayName("oversubscribed withdrawals: exactly the affordable number succeed, and never more")
    void oversubscriptionCannotOverdraw() throws Exception {
        // The test that catches a check-then-write race. 10 withdrawals are
        // affordable and 25 are attempted simultaneously. If the balance check could
        // race, more than 10 would succeed and the wallet would go negative.
        String customer = newCustomer("oversubscribed");
        long each = 100;
        int affordable = 10;
        int attempts = 25;
        fund(customer, "USD", affordable * each);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runConcurrently(attempts, () -> {
            try {
                retry.execute("payout", () -> transfers.payout(customer, "USD", each,
                        newReference("race"), Instant.now(), UUID.randomUUID()));
                succeeded.incrementAndGet();
            } catch (LedgerException.InsufficientFunds e) {
                rejected.incrementAndGet();
            }
            return null;
        });

        assertThat(succeeded.get()).isEqualTo(affordable);
        assertThat(rejected.get()).isEqualTo(attempts - affordable);
        assertThat(walletBalance(customer, "USD")).isZero();
        assertBooksBalance();
    }

    @Test
    @DisplayName("a fan-out of transfers from one wallet loses nothing")
    void fanOutConservesTotal() throws Exception {
        String source = newCustomer("fan-source");
        int recipients = 20;
        long each = 250;
        fund(source, "EUR", recipients * each);

        List<String> targets = new ArrayList<>();
        for (int i = 0; i < recipients; i++) {
            targets.add(newCustomer("fan-target-" + i));
        }

        List<Callable<Void>> work = new ArrayList<>();
        for (String target : targets) {
            work.add(() -> {
                retry.execute("transfer", () -> transfers.transfer(source, target, "EUR", each,
                        newReference("fanout"), Instant.now(), UUID.randomUUID()));
                return null;
            });
        }
        runAll(work);

        assertThat(walletBalance(source, "EUR")).isZero();
        long received = targets.stream().mapToLong(t -> walletBalance(t, "EUR")).sum();
        assertThat(received).isEqualTo(recipients * each);
        assertBooksBalance();
    }

    @Test
    @DisplayName("the projected balance always equals a fresh replay of the journal")
    void projectionSurvivesContention() throws Exception {
        String customer = newCustomer("replay-check");
        int rounds = 24;
        fund(customer, "GBP", rounds * 100);

        runConcurrently(rounds, () -> {
            retry.execute("payout", () -> transfers.payout(customer, "GBP", 100,
                    newReference("replay"), Instant.now(), UUID.randomUUID()));
            return null;
        });

        String walletCode = ChartOfAccounts.wallet(customer, "GBP");
        AccountBalance projected = balances.balanceOf(walletCode);
        BalanceRepository.RebuiltBalance rebuilt = balances.rebuild(walletCode);

        assertThat(rebuilt.balanceMinor()).isEqualTo(projected.signedBalanceMinor());
        assertThat(rebuilt.entryCount()).isEqualTo(projected.entryCount());
    }

    /** Starts every task at the same instant, so the contention is real rather than incidental. */
    private void runConcurrently(int threads, Callable<Void> task) throws Exception {
        List<Callable<Void>> work = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            work.add(task);
        }
        runAll(work);
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
