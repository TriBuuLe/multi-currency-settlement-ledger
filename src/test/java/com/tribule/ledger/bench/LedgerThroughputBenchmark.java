package com.tribule.ledger.bench;

import com.tribule.ledger.ledger.ChartOfAccounts;
import com.tribule.ledger.ledger.LedgerException;
import com.tribule.ledger.ledger.TransactionalRetry;
import com.tribule.ledger.support.AbstractLedgerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Measures the write path, and checks the books still add up afterwards.
 *
 * <p>Not part of the normal test run -- the file is named {@code *Benchmark} so surefire
 * skips it. Run it explicitly:
 *
 * <pre>
 *   mvn -B test -Dtest=LedgerThroughputBenchmark
 * </pre>
 *
 * <p>Two profiles, because they measure genuinely different limits:
 *
 * <ul>
 *   <li><b>spread</b> -- transfers between distinct wallets. Distinct accounts mean
 *       distinct balance rows, so there is little contention and the limit is the
 *       database's commit rate.
 *   <li><b>hot account</b> -- every transfer debits the same wallet. All of them contend
 *       on one balance row and serialise. This is the honest number, because every real
 *       payments system has an account everything flows through.
 * </ul>
 *
 * <p>The figures depend entirely on the machine and on Postgres running in a container,
 * so they are useful as a relative comparison between the two profiles and as a
 * regression check, not as an absolute claim.
 */
class LedgerThroughputBenchmark extends AbstractLedgerTest {

    private static final int THREADS = 16;
    private static final int OPERATIONS_PER_THREAD = 120;

    @Autowired private TransactionalRetry retry;

    @Test
    @DisplayName("throughput on distinct accounts versus one hot account")
    void measureWritePath() throws Exception {
        // Warm the JIT, the connection pool, and Postgres' plan cache before measuring.
        String warm = newCustomer("bench-warm");
        fund(warm, "USD", 1_000_000);
        for (int i = 0; i < 200; i++) {
            transfers.payout(warm, "USD", 10, newReference("warm"), Instant.now(), UUID.randomUUID());
        }

        Result spread = runSpread();
        Result hot = runHotAccount();

        System.out.printf("%n%-16s %10s %10s %10s %10s %10s %10s%n",
                "profile", "ops", "ok", "tps", "p50 ms", "p99 ms", "retries");
        System.out.printf("%-16s %10d %10d %10.0f %10.2f %10.2f %10d%n",
                "spread", spread.attempted, spread.succeeded, spread.tps(), spread.p50(), spread.p99(),
                spread.retries);
        System.out.printf("%-16s %10d %10d %10.0f %10.2f %10.2f %10d%n",
                "hot account", hot.attempted, hot.succeeded, hot.tps(), hot.p50(), hot.p99(), hot.retries);
        System.out.printf("%ntrial balance after %d writes:%n", spread.succeeded + hot.succeeded);
        balances.trialBalance().forEach(line ->
                System.out.printf("  %s residual=%d across %d accounts%n",
                        line.currencyCode(), line.residualMinor(), line.accountCount()));

        // The only assertion that matters: load must not break the books.
        assertBooksBalance();
        assertThat(spread.succeeded).isEqualTo(spread.attempted);
        assertThat(hot.succeeded).isEqualTo(hot.attempted);
    }

    /** Transfers between distinct wallet pairs: little contention. */
    private Result runSpread() throws Exception {
        List<String> senders = new ArrayList<>();
        List<String> receivers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String sender = newCustomer("bench-from-" + i);
            fund(sender, "USD", (long) OPERATIONS_PER_THREAD * 100 + 1000);
            senders.add(sender);
            receivers.add(newCustomer("bench-to-" + i));
        }
        return measure("spread", thread -> () -> transfers.transfer(
                senders.get(thread), receivers.get(thread), "USD", 100,
                newReference("bench-spread"), Instant.now(), UUID.randomUUID()));
    }

    /** Every thread debits the same wallet: maximum contention on one balance row. */
    private Result runHotAccount() throws Exception {
        String hot = newCustomer("bench-hot");
        fund(hot, "USD", (long) THREADS * OPERATIONS_PER_THREAD * 10 + 10_000);
        List<String> sinks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            sinks.add(newCustomer("bench-sink-" + i));
        }
        return measure("hot", thread -> () -> transfers.transfer(
                hot, sinks.get(thread), "USD", 10,
                newReference("bench-hot"), Instant.now(), UUID.randomUUID()));
    }

    private Result measure(String name, java.util.function.IntFunction<Runnable> workFor) throws Exception {
        long[] latencies = new long[THREADS * OPERATIONS_PER_THREAD];
        AtomicInteger index = new AtomicInteger();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        CountDownLatch startLine = new CountDownLatch(1);

        List<Callable<Void>> work = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            Runnable operation = workFor.apply(t);
            work.add(() -> {
                startLine.await();
                for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                    long startedAt = System.nanoTime();
                    try {
                        retry.execute(name, operation);
                        succeeded.incrementAndGet();
                    } catch (LedgerException e) {
                        retries.incrementAndGet();
                    }
                    latencies[index.getAndIncrement()] = System.nanoTime() - startedAt;
                }
                return null;
            });
        }

        long startedAt = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : work) {
                futures.add(pool.submit(task));
            }
            startLine.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        long[] sorted = Arrays.copyOf(latencies, index.get());
        Arrays.sort(sorted);
        return new Result(name, index.get(), succeeded.get(), retries.get(), elapsed, sorted);
    }

    private record Result(String name, int attempted, int succeeded, int retries,
                          Duration elapsed, long[] sortedLatencies) {

        double tps() {
            return succeeded / Math.max(0.001, elapsed.toNanos() / 1_000_000_000.0);
        }

        double p50() {
            return percentile(0.50);
        }

        double p99() {
            return percentile(0.99);
        }

        private double percentile(double fraction) {
            if (sortedLatencies.length == 0) {
                return 0;
            }
            int at = Math.clamp((int) Math.ceil(fraction * sortedLatencies.length) - 1,
                    0, sortedLatencies.length - 1);
            return sortedLatencies[at] / 1_000_000.0;
        }
    }
}
