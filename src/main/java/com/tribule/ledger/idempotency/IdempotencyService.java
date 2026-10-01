package com.tribule.ledger.idempotency;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Makes a write happen at most once, however many times the client asks.
 *
 * <p>Retries are not an edge case in payments: a timeout tells the client
 * nothing about whether the server acted, so a well-behaved client retries, and
 * the ledger has to be the thing that refuses to post twice. The rule that makes
 * it work is that uniqueness is decided by the database, not by a read-then-write
 * in application code -- two simultaneous retries both attempt an INSERT and
 * exactly one of them can win a primary key.
 *
 * <p>The three outcomes, all of them deliberate:
 *
 * <ul>
 *   <li>first arrival wins the INSERT, does the work, and stores its response;
 *   <li>a later arrival with the same body replays the stored response;
 *   <li>a later arrival with a <em>different</em> body is rejected outright
 *       rather than being handed someone else's result.
 * </ul>
 */
@Service
public class IdempotencyService {

    /** What happened, and whether the caller is looking at fresh work or a replay. */
    public record Outcome<T>(T value, boolean replayed, UUID transactionId) {
    }

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRepository repository;
    private final ObjectMapper canonicalMapper;
    private final ObjectMapper objectMapper;
    private final Counter replays;
    private final Counter conflicts;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper, MeterRegistry registry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        // Field order must not change the fingerprint, or a client that
        // serialises its JSON differently on retry would look like a new request.
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.replays = Counter.builder("ledger.idempotency.replays")
                .description("Requests served from a stored response instead of being re-executed")
                .register(registry);
        this.conflicts = Counter.builder("ledger.idempotency.conflicts")
                .description("Idempotency keys reused with a different request body")
                .register(registry);
    }

    /**
     * @param scope       keyspace, so a key reused across unrelated endpoints is not a collision
     * @param key         the client's Idempotency-Key
     * @param request     the request body, fingerprinted to detect key reuse
     * @param responseType type to deserialise a replayed response into
     * @param work        the actual work, handed the transaction id reserved for it
     */
    public <T> Outcome<T> execute(String scope, String key, Object request, Class<T> responseType,
                                  Function<UUID, T> work) {
        String fingerprint = fingerprint(request);
        UUID reservedTransactionId = UUID.randomUUID();

        if (!repository.tryClaim(scope, key, fingerprint, reservedTransactionId)) {
            return replay(scope, key, fingerprint, responseType);
        }

        try {
            T value = work.apply(reservedTransactionId);
            repository.complete(scope, key, 200, value);
            return new Outcome<>(value, false, reservedTransactionId);
        } catch (RuntimeException e) {
            // The work rolled back, so the key must not stay claimed -- a client
            // retrying after a 500 deserves a real second attempt.
            repository.release(scope, key);
            throw e;
        }
    }

    private <T> Outcome<T> replay(String scope, String key, String fingerprint, Class<T> responseType) {
        IdempotencyRepository.Record existing = repository.find(scope, key)
                .orElseThrow(() -> new IdempotencyException.InProgress(scope, key));

        if (!existing.requestHash().equals(fingerprint)) {
            conflicts.increment();
            throw new IdempotencyException.KeyReused(scope, key);
        }
        if (!existing.isCompleted() || existing.responseBody() == null) {
            throw new IdempotencyException.InProgress(scope, key);
        }

        replays.increment();
        log.debug("replaying stored response for {}/{}", scope, key);
        try {
            return new Outcome<>(
                    objectMapper.readValue(existing.responseBody(), responseType),
                    true,
                    existing.transactionId());
        } catch (Exception e) {
            throw new IdempotencyException(
                    "stored response for key '%s' could not be replayed: %s".formatted(key, e.getMessage()));
        }
    }

    public Optional<IdempotencyRepository.Record> inspect(String scope, String key) {
        return repository.find(scope, key);
    }

    private String fingerprint(Object request) {
        try {
            byte[] canonical = canonicalMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        } catch (Exception e) {
            throw new IdempotencyException("request body could not be fingerprinted: " + e.getMessage());
        }
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
