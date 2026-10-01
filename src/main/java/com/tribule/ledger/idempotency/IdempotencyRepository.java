package com.tribule.ledger.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IdempotencyRepository {

    /** A claim on a key, as stored. */
    public record Record(String scope, String key, String requestHash, String status,
                         Integer responseStatus, String responseBody, UUID transactionId) {

        public boolean isCompleted() {
            return "COMPLETED".equals(status);
        }
    }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Tries to claim a key, in its own committed transaction.
     *
     * <p>It has to commit before the work starts, otherwise a concurrent retry
     * would not be able to see the claim and both copies would run. The
     * pre-allocated {@code transactionId} is stored with the claim so that a
     * crash between the work committing and the claim being marked complete is
     * recoverable: the reaper can ask the journal whether that transaction
     * exists.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaim(String scope, String key, String requestHash, UUID transactionId) {
        try {
            jdbc.sql("""
                    INSERT INTO idempotency_record
                        (scope, idempotency_key, request_hash, status, transaction_id)
                    VALUES (?, ?, ?, 'IN_FLIGHT', ?)
                    """)
                    .params(scope, key, requestHash, transactionId)
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Record> find(String scope, String key) {
        return jdbc.sql("""
                SELECT scope, idempotency_key, request_hash, status, response_status,
                       response_body::text AS response_body, transaction_id
                  FROM idempotency_record
                 WHERE scope = ? AND idempotency_key = ?
                """)
                .params(scope, key)
                .query((rs, n) -> new Record(
                        rs.getString("scope"),
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        rs.getObject("response_status") == null ? null : rs.getInt("response_status"),
                        rs.getString("response_body"),
                        rs.getObject("transaction_id", UUID.class)))
                .optional();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String scope, String key, int responseStatus, Object responseBody) {
        String json;
        try {
            json = objectMapper.writeValueAsString(responseBody);
        } catch (Exception e) {
            throw new IdempotencyException("could not serialise response for replay: " + e.getMessage());
        }
        jdbc.sql("""
                UPDATE idempotency_record
                   SET status = 'COMPLETED', response_status = ?, response_body = ?::jsonb, completed_at = now()
                 WHERE scope = ? AND idempotency_key = ?
                """)
                .params(responseStatus, json, scope, key)
                .update();
    }

    /**
     * Drops a claim whose work failed, so that a client retry is allowed to try
     * again rather than being locked out by its own earlier 500.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String scope, String key) {
        jdbc.sql("DELETE FROM idempotency_record WHERE scope = ? AND idempotency_key = ? AND status = 'IN_FLIGHT'")
                .params(scope, key)
                .update();
    }

    /** Claims stuck in flight for longer than they should be: candidates for recovery. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Record> findStaleInFlight(Duration olderThan, int limit) {
        Instant cutoff = Instant.now().minus(olderThan);
        return jdbc.sql("""
                SELECT scope, idempotency_key, request_hash, status, response_status,
                       response_body::text AS response_body, transaction_id
                  FROM idempotency_record
                 WHERE status = 'IN_FLIGHT' AND created_at < ?
                 ORDER BY created_at
                 LIMIT ?
                """)
                .params(cutoff.atOffset(java.time.ZoneOffset.UTC), limit)
                .query((rs, n) -> new Record(
                        rs.getString("scope"),
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        rs.getObject("response_status") == null ? null : rs.getInt("response_status"),
                        rs.getString("response_body"),
                        rs.getObject("transaction_id", UUID.class)))
                .list();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompletedWithoutBody(String scope, String key, int responseStatus) {
        jdbc.sql("""
                UPDATE idempotency_record
                   SET status = 'COMPLETED', response_status = ?, completed_at = now()
                 WHERE scope = ? AND idempotency_key = ? AND status = 'IN_FLIGHT'
                """)
                .params(responseStatus, scope, key)
                .update();
    }
}
