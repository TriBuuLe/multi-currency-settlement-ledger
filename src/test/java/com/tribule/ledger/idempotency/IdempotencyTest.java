package com.tribule.ledger.idempotency;

import com.tribule.ledger.payments.TransferService;
import com.tribule.ledger.support.AbstractLedgerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * At-most-once writes, however many times the client asks.
 *
 * <p>A timeout tells a client nothing about whether the server acted, so a correct
 * client retries, and the ledger has to be the thing that refuses to post twice.
 * The property being tested is not "duplicate requests return the same shape" -- it
 * is that the journal contains exactly one transaction afterwards.
 */
class IdempotencyTest extends AbstractLedgerTest {

    private record Receipt(UUID transactionId, long amountMinor, String currency) {
    }

    @Autowired private IdempotencyService idempotency;
    @Autowired private IdempotencyRepository records;
    @Autowired private IdempotencyReaper reaper;

    @Test
    @DisplayName("retrying with the same key and body posts once and replays the response")
    void retryReplaysInsteadOfReposting() {
        String customer = newCustomer("replay");
        fund(customer, "USD", 50_000);
        String key = "key-" + UUID.randomUUID();
        Map<String, Object> request = Map.of("customer", customer, "amount", 1_000);
        AtomicInteger executions = new AtomicInteger();

        IdempotencyService.Outcome<Receipt> first = idempotency.execute(
                "test.payout", key, request, Receipt.class, transactionId -> {
                    executions.incrementAndGet();
                    TransferService.TransferResult result = transfers.payout(customer, "USD", 1_000,
                            newReference("idem"), Instant.now(), transactionId);
                    return new Receipt(result.transaction().transaction().id(), 1_000, "USD");
                });

        IdempotencyService.Outcome<Receipt> second = idempotency.execute(
                "test.payout", key, request, Receipt.class, transactionId -> {
                    executions.incrementAndGet();
                    throw new AssertionError("the work must not run a second time");
                });

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(executions.get()).isEqualTo(1);
        assertThat(second.value()).isEqualTo(first.value());
        assertThat(walletBalance(customer, "USD"))
                .as("the money left the wallet exactly once")
                .isEqualTo(49_000);
    }

    @Test
    @DisplayName("the same key with a different body is rejected, not silently satisfied")
    void reusedKeyWithDifferentBodyIsRejected() {
        // The dangerous case. Returning the first request's result here is how a
        // 10 USD transfer gets reported as a successful 10,000 USD transfer.
        String customer = newCustomer("key-reuse");
        fund(customer, "USD", 20_000);
        String key = "key-" + UUID.randomUUID();

        idempotency.execute("test.payout", key, Map.of("amount", 1_000), Receipt.class,
                transactionId -> {
                    transfers.payout(customer, "USD", 1_000, newReference("first"), Instant.now(), transactionId);
                    return new Receipt(transactionId, 1_000, "USD");
                });

        assertThatThrownBy(() -> idempotency.execute("test.payout", key, Map.of("amount", 9_999),
                Receipt.class, transactionId -> new Receipt(transactionId, 9_999, "USD")))
                .isInstanceOf(IdempotencyException.KeyReused.class)
                .hasMessageContaining("different request body");

        assertThat(walletBalance(customer, "USD")).isEqualTo(19_000);
    }

    @Test
    @DisplayName("field ordering in the request body does not change the fingerprint")
    void fingerprintIsOrderInsensitive() {
        String key = "key-" + UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();

        idempotency.execute("test.fingerprint", key, Map.of("a", 1, "b", 2), Receipt.class,
                id -> {
                    executions.incrementAndGet();
                    return new Receipt(id, 1, "USD");
                });
        IdempotencyService.Outcome<Receipt> reordered = idempotency.execute(
                "test.fingerprint", key, Map.of("b", 2, "a", 1), Receipt.class,
                id -> {
                    executions.incrementAndGet();
                    return new Receipt(id, 1, "USD");
                });

        assertThat(reordered.replayed()).isTrue();
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent duplicates execute the work exactly once")
    void concurrentDuplicatesExecuteOnce() throws Exception {
        // Two retries arriving at the same instant both try to INSERT the claim.
        // Only one can win a primary key, which is the whole mechanism.
        String customer = newCustomer("concurrent-idem");
        fund(customer, "USD", 100_000);
        String key = "key-" + UUID.randomUUID();
        Map<String, Object> request = Map.of("customer", customer, "amount", 2_500);

        AtomicInteger executions = new AtomicInteger();
        AtomicInteger replays = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        int attempts = 12;

        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            work.add(() -> {
                try {
                    IdempotencyService.Outcome<Receipt> outcome = idempotency.execute(
                            "test.concurrent", key, request, Receipt.class, transactionId -> {
                                executions.incrementAndGet();
                                TransferService.TransferResult result = transfers.payout(customer, "USD", 2_500,
                                        newReference("concurrent-idem"), Instant.now(), transactionId);
                                return new Receipt(result.transaction().transaction().id(), 2_500, "USD");
                            });
                    if (outcome.replayed()) {
                        replays.incrementAndGet();
                    }
                } catch (IdempotencyException.InProgress e) {
                    // The honest answer while the winner is still working: the client
                    // is told to retry rather than handed a guess.
                    inFlight.incrementAndGet();
                }
                return null;
            });
        }
        runAll(work);

        assertThat(executions.get())
                .as("exactly one attempt may do the work")
                .isEqualTo(1);
        assertThat(replays.get() + inFlight.get()).isEqualTo(attempts - 1);
        assertThat(walletBalance(customer, "USD"))
                .as("%d concurrent retries must move the money once", attempts)
                .isEqualTo(97_500);
        assertBooksBalance();
    }

    @Test
    @DisplayName("a failed attempt releases the key so a genuine retry can succeed")
    void failedWorkReleasesTheKey() {
        String customer = newCustomer("failed-work");
        fund(customer, "USD", 5_000);
        String key = "key-" + UUID.randomUUID();
        Map<String, Object> request = Map.of("customer", customer);

        assertThatThrownBy(() -> idempotency.execute("test.failure", key, request, Receipt.class,
                transactionId -> {
                    throw new IllegalStateException("downstream blew up");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(records.find("test.failure", key))
                .as("a 500 must not lock the client out of its own key forever")
                .isEmpty();

        IdempotencyService.Outcome<Receipt> retried = idempotency.execute(
                "test.failure", key, request, Receipt.class, transactionId -> {
                    TransferService.TransferResult result = transfers.payout(customer, "USD", 1_000,
                            newReference("retry"), Instant.now(), transactionId);
                    return new Receipt(result.transaction().transaction().id(), 1_000, "USD");
                });

        assertThat(retried.replayed()).isFalse();
        assertThat(walletBalance(customer, "USD")).isEqualTo(4_000);
    }

    @Test
    @DisplayName("the reaper completes a claim whose work landed before the crash")
    void reaperCompletesWorkThatLanded() {
        // Simulates a process dying between the work committing and the claim being
        // marked complete. The claim carries the transaction id it reserved, so the
        // journal can answer whether the write happened.
        String customer = newCustomer("crash-after-commit");
        fund(customer, "USD", 10_000);
        String key = "key-" + UUID.randomUUID();
        UUID reservedTransactionId = UUID.randomUUID();

        records.tryClaim("test.crash", key, "hash", reservedTransactionId);
        transfers.payout(customer, "USD", 3_000, newReference("landed"), Instant.now(), reservedTransactionId);

        assertThat(reaper.reapOnce()).isPositive();

        IdempotencyRepository.Record recovered = records.find("test.crash", key).orElseThrow();
        assertThat(recovered.isCompleted())
                .as("the work is in the journal, so the claim must be completed, not released")
                .isTrue();
    }

    @Test
    @DisplayName("the reaper releases a claim whose work never landed")
    void reaperReleasesWorkThatRolledBack() {
        String key = "key-" + UUID.randomUUID();
        records.tryClaim("test.rollback", key, "hash", UUID.randomUUID());

        assertThat(reaper.reapOnce()).isPositive();
        assertThat(records.find("test.rollback", key))
                .as("nothing was written, so the key must be free for a real retry")
                .isEmpty();
    }

    @Test
    @DisplayName("scopes keep identical keys on different endpoints from colliding")
    void scopesAreIndependent() {
        String key = "shared-" + UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();

        idempotency.execute("scope.one", key, Map.of("x", 1), Receipt.class,
                id -> { executions.incrementAndGet(); return new Receipt(id, 1, "USD"); });
        idempotency.execute("scope.two", key, Map.of("x", 1), Receipt.class,
                id -> { executions.incrementAndGet(); return new Receipt(id, 1, "USD"); });

        assertThat(executions.get()).isEqualTo(2);
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
