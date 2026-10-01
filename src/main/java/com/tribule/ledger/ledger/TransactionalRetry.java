package com.tribule.ledger.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Retries a unit of work that lost a concurrency race.
 *
 * <p>Two transfers touching the same account contend on that account's balance
 * row. Under load that surfaces as a lock timeout, a deadlock, or -- if the
 * isolation level is raised to SERIALIZABLE -- a serialization failure. All
 * three are transient and all three are safe to retry, because the work is
 * driven by an idempotency key and a retry cannot double-post.
 *
 * <p>The retry has to live outside the transaction: a rolled-back transaction
 * cannot be resumed, only re-run. Callers therefore wrap the transactional bean
 * method rather than the reverse, and the counter here is what tells you whether
 * contention is actually costing anything in production.
 */
@Component
public class TransactionalRetry {

    private static final Logger log = LoggerFactory.getLogger(TransactionalRetry.class);

    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MICROS = 250;

    private final Counter retries;
    private final Counter exhausted;

    public TransactionalRetry(MeterRegistry registry) {
        this.retries = Counter.builder("ledger.retry.attempts")
                .description("Transactions re-run after losing a concurrency race")
                .register(registry);
        this.exhausted = Counter.builder("ledger.retry.exhausted")
                .description("Transactions abandoned after exhausting retries")
                .register(registry);
    }

    public <T> T execute(String operation, Supplier<T> work) {
        ConcurrencyFailureException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return work.get();
            } catch (ConcurrencyFailureException e) {
                last = e;
                retries.increment();
                log.debug("{} lost a concurrency race on attempt {}/{}: {}",
                        operation, attempt, MAX_ATTEMPTS, e.getMostSpecificCause().getMessage());
                backoff(attempt);
            }
        }
        exhausted.increment();
        throw new LedgerException(
                "%s could not complete after %d attempts under contention".formatted(operation, MAX_ATTEMPTS), last);
    }

    public void execute(String operation, Runnable work) {
        execute(operation, () -> {
            work.run();
            return null;
        });
    }

    /** Exponential backoff with full jitter, so retriers do not re-collide in lockstep. */
    private void backoff(int attempt) {
        long ceiling = BASE_BACKOFF_MICROS << (attempt - 1);
        long micros = ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MICROS, ceiling + 1);
        try {
            Thread.sleep(micros / 1000, (int) (micros % 1000) * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LedgerException("interrupted while backing off", e);
        }
    }
}
