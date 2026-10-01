package com.tribule.ledger.idempotency;

import com.tribule.ledger.ledger.JournalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Cleans up claims orphaned by a crash.
 *
 * <p>If the process dies after the work commits but before the claim is marked
 * complete, the key is stuck IN_FLIGHT and the client would be told "still in
 * progress" forever. Because the claim stores the transaction id reserved for
 * that work, recovery is a question the journal can answer: if that transaction
 * exists the work did happen and the claim is completed; if it does not, the
 * work was rolled back and the claim is released for a genuine retry.
 *
 * <p>This is the part of idempotency that most implementations leave out, and it
 * is the part that actually decides what a client sees after a deploy restarts a
 * pod mid-request.
 */
@Component
public class IdempotencyReaper {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyReaper.class);
    private static final int BATCH = 200;

    private final IdempotencyRepository idempotency;
    private final JournalRepository journal;
    private final Duration staleAfter;

    public IdempotencyReaper(IdempotencyRepository idempotency,
                             JournalRepository journal,
                             @Value("${ledger.idempotency.stale-after:PT2M}") Duration staleAfter) {
        this.idempotency = idempotency;
        this.journal = journal;
        this.staleAfter = staleAfter;
    }

    @Scheduled(fixedDelayString = "${ledger.idempotency.reap-interval-ms:60000}",
            initialDelayString = "${ledger.idempotency.reap-initial-delay-ms:45000}")
    public void reap() {
        int recovered = reapOnce();
        if (recovered > 0) {
            log.info("resolved {} orphaned idempotency claim(s)", recovered);
        }
    }

    /** Exposed so tests can drive a reap without waiting for the scheduler. */
    public int reapOnce() {
        List<IdempotencyRepository.Record> stale = idempotency.findStaleInFlight(staleAfter, BATCH);
        int resolved = 0;
        for (IdempotencyRepository.Record record : stale) {
            boolean workLanded = record.transactionId() != null
                    && journal.findTransaction(record.transactionId()).isPresent();
            if (workLanded) {
                // The write is real; the response body is gone, so the client is
                // pointed at the transaction rather than given a fabricated one.
                idempotency.markCompletedWithoutBody(record.scope(), record.key(), 200);
            } else {
                idempotency.release(record.scope(), record.key());
            }
            resolved++;
        }
        return resolved;
    }
}
