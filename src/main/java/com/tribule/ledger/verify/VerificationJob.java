package com.tribule.ledger.verify;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs verification on a schedule and publishes the result as a gauge.
 *
 * <p>{@code ledger.verification.findings} is the single number worth alerting on in
 * this whole service. If it is ever above zero, the books do not add up and
 * everything else -- latency, throughput, error rate -- stops mattering.
 */
@Component
public class VerificationJob {

    private static final Logger log = LoggerFactory.getLogger(VerificationJob.class);

    private final LedgerVerificationService verification;
    private final AtomicInteger findingCount = new AtomicInteger();
    private final AtomicInteger healthy = new AtomicInteger(1);

    public VerificationJob(LedgerVerificationService verification, MeterRegistry registry) {
        this.verification = verification;
        registry.gauge("ledger.verification.findings", findingCount);
        registry.gauge("ledger.verification.healthy", healthy);
    }

    @Scheduled(fixedDelayString = "${ledger.verification.interval-ms:300000}", initialDelayString = "${ledger.verification.initial-delay-ms:15000}")
    public void run() {
        try {
            VerificationReport report = verification.verify();
            findingCount.set(report.findings().size());
            healthy.set(report.healthy() ? 1 : 0);
            if (report.healthy()) {
                log.debug("verification passed: {} transaction(s), {} entries, {} ms",
                        report.transactions(), report.entries(), report.durationMillis());
            }
        } catch (RuntimeException e) {
            // A verifier that throws must not look like a verifier that passed.
            healthy.set(0);
            log.error("verification could not complete", e);
        }
    }
}
