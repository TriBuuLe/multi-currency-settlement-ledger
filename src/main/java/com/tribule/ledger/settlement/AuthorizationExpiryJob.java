package com.tribule.ledger.settlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Releases held funds on quotes nobody ever settled. */
@Component
public class AuthorizationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationExpiryJob.class);
    private static final int BATCH = 500;

    private final AuthorizationService authorizations;

    public AuthorizationExpiryJob(AuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    @Scheduled(fixedDelayString = "${ledger.authorization.expiry-interval-ms:30000}",
            initialDelayString = "${ledger.authorization.expiry-initial-delay-ms:20000}")
    public void expire() {
        int expired = authorizations.expireDue(Instant.now(), BATCH);
        if (expired > 0) {
            log.info("expired {} authorization(s) and released their holds", expired);
        }
    }
}
