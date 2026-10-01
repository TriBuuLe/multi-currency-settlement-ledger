package com.tribule.ledger.web;

import com.tribule.ledger.fx.FxException;
import com.tribule.ledger.idempotency.IdempotencyException;
import com.tribule.ledger.ledger.LedgerException;
import com.tribule.ledger.money.MoneyException;
import com.tribule.ledger.recon.ReconciliationException;
import com.tribule.ledger.settlement.SettlementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns domain failures into RFC 9457 problem responses.
 *
 * <p>The status codes are chosen deliberately, because a client's retry behaviour
 * depends on them:
 *
 * <ul>
 *   <li><b>409</b> for insufficient funds, a stale authorization version, or a key
 *       still in flight -- transient, retry later and it may well work.
 *   <li><b>422</b> for an unbalanced posting, a reused idempotency key, or a
 *       missing rate -- the request is wrong or cannot be priced, and retrying it
 *       unchanged will fail identically.
 * </ul>
 *
 * <p>Collapsing those into one code is how a client ends up hammering a request
 * that can never succeed, or giving up on one that would have.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BASE = "https://github.com/TriBuuLe/multi-currency-settlement-ledger/problems/";

    @ExceptionHandler(LedgerException.Unbalanced.class)
    public ProblemDetail unbalanced(LedgerException.Unbalanced e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "unbalanced-transaction", "Transaction does not balance", e);
    }

    @ExceptionHandler(LedgerException.InsufficientFunds.class)
    public ProblemDetail insufficientFunds(LedgerException.InsufficientFunds e) {
        return problem(HttpStatus.CONFLICT, "insufficient-funds", "Insufficient funds", e);
    }

    @ExceptionHandler({LedgerException.AccountNotFound.class, LedgerException.TransactionNotFound.class})
    public ProblemDetail ledgerNotFound(LedgerException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", e);
    }

    @ExceptionHandler(LedgerException.AlreadyReversed.class)
    public ProblemDetail alreadyReversed(LedgerException.AlreadyReversed e) {
        return problem(HttpStatus.CONFLICT, "already-reversed", "Already reversed", e);
    }

    @ExceptionHandler(LedgerException.class)
    public ProblemDetail ledger(LedgerException e) {
        log.warn("ledger rejected a write: {}", e.getMessage());
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "ledger-rejected", "Ledger rejected the write", e);
    }

    @ExceptionHandler(IdempotencyException.KeyReused.class)
    public ProblemDetail keyReused(IdempotencyException.KeyReused e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reused",
                "Idempotency key reused with a different body", e);
    }

    @ExceptionHandler(IdempotencyException.InProgress.class)
    public ProblemDetail inProgress(IdempotencyException.InProgress e) {
        return problem(HttpStatus.CONFLICT, "request-in-flight", "An earlier attempt is still running", e);
    }

    @ExceptionHandler(FxException.RateNotAvailable.class)
    public ProblemDetail rateNotAvailable(FxException.RateNotAvailable e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "rate-not-available", "No usable FX rate", e);
    }

    @ExceptionHandler(FxException.UnknownRate.class)
    public ProblemDetail unknownRate(FxException.UnknownRate e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", e);
    }

    @ExceptionHandler(FxException.class)
    public ProblemDetail fx(FxException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "fx-error", "FX request rejected", e);
    }

    @ExceptionHandler(SettlementException.NotFound.class)
    public ProblemDetail authorizationNotFound(SettlementException.NotFound e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", e);
    }

    @ExceptionHandler({SettlementException.WrongState.class, SettlementException.ConcurrentModification.class})
    public ProblemDetail authorizationConflict(SettlementException e) {
        return problem(HttpStatus.CONFLICT, "authorization-conflict", "Authorization is not in the required state", e);
    }

    @ExceptionHandler(SettlementException.Expired.class)
    public ProblemDetail expired(SettlementException.Expired e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "authorization-expired", "Authorization expired", e);
    }

    @ExceptionHandler(SettlementException.class)
    public ProblemDetail settlement(SettlementException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "settlement-error", "Settlement rejected", e);
    }

    @ExceptionHandler(ReconciliationException.BatchNotFound.class)
    public ProblemDetail batchNotFound(ReconciliationException.BatchNotFound e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", e);
    }

    @ExceptionHandler(ReconciliationException.DuplicateBatch.class)
    public ProblemDetail duplicateBatch(ReconciliationException.DuplicateBatch e) {
        return problem(HttpStatus.CONFLICT, "duplicate-statement", "Statement already imported", e);
    }

    @ExceptionHandler(ReconciliationException.class)
    public ProblemDetail reconciliation(ReconciliationException e) {
        return problem(HttpStatus.BAD_REQUEST, "malformed-statement", "Statement could not be processed", e);
    }

    @ExceptionHandler(MoneyException.class)
    public ProblemDetail money(MoneyException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-amount", "Invalid amount or currency", e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail illegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail missingHeader(MissingRequestHeaderException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "missing required header: " + e.getHeaderName());
        detail.setType(URI.create(BASE + "missing-header"));
        detail.setTitle("Missing header");
        if ("Idempotency-Key".equalsIgnoreCase(e.getHeaderName())) {
            detail.setProperty("hint", "every mutating endpoint requires a unique Idempotency-Key so that a "
                    + "retry after a timeout cannot post the same transaction twice");
        }
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fields.put(error.getField(), error.getDefaultMessage()));
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request validation failed");
        detail.setType(URI.create(BASE + "validation-failed"));
        detail.setTitle("Validation failed");
        detail.setProperty("fields", fields);
        return detail;
    }

    private ProblemDetail problem(HttpStatus status, String type, String title, Exception e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        detail.setType(URI.create(BASE + type));
        detail.setTitle(title);
        return detail;
    }
}
