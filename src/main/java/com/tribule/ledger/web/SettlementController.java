package com.tribule.ledger.web;

import com.tribule.ledger.idempotency.IdempotencyService;
import com.tribule.ledger.ledger.TransactionalRetry;
import com.tribule.ledger.settlement.AuthorizationService;
import com.tribule.ledger.settlement.SettlementService;
import com.tribule.ledger.web.dto.Requests;
import com.tribule.ledger.web.dto.Responses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/authorizations")
@Tag(name = "Authorizations", description = "Quote, hold, settle, and release FX authorizations")
public class SettlementController {

    private final AuthorizationService authorizations;
    private final SettlementService settlements;
    private final IdempotencyService idempotency;
    private final TransactionalRetry retry;
    private final ViewMapper views;

    public SettlementController(AuthorizationService authorizations, SettlementService settlements,
                               IdempotencyService idempotency, TransactionalRetry retry, ViewMapper views) {
        this.authorizations = authorizations;
        this.settlements = settlements;
        this.idempotency = idempotency;
        this.retry = retry;
        this.views = views;
    }

    @PostMapping
    @Operation(summary = "Authorize an FX conversion",
            description = """
                    Prices the conversion at the rate as known right now, reserves the customer's funds in a
                    hold account, and records the buy-side obligation off the balance sheet. No buy currency
                    moves yet, which is exactly why exposure starts here.
                    """)
    public ResponseEntity<Responses.AuthorizationCreatedView> authorize(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Requests.AuthorizeRequest request) {
        return respond(idempotency.execute("authorizations.open", key, request,
                Responses.AuthorizationCreatedView.class,
                transactionId -> retry.execute("authorize", () -> views.authorizationCreated(
                        authorizations.authorize(new AuthorizationService.AuthorizeCommand(
                                request.reference(), request.customerId(), request.sellCurrency(),
                                request.buyCurrency(), request.sellAmountMinor(), request.authorizedAt(),
                                request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds())),
                                transactionId)))));
    }

    @PostMapping("/{id}/settlement")
    @Operation(summary = "Settle an authorization",
            description = """
                    Pays the customer exactly what was quoted, values the sell side at the settlement rate,
                    and posts the difference to realized FX P&L. The response explains which way the rate
                    moved and what it cost or earned.
                    """)
    public ResponseEntity<Responses.SettlementView> settle(
            @RequestHeader("Idempotency-Key") String key,
            @PathVariable UUID id,
            @RequestBody(required = false) Requests.SettleRequest request) {
        Requests.SettleRequest body = request == null ? new Requests.SettleRequest(null) : request;
        return respond(idempotency.execute("authorizations.settle." + id, key, body,
                Responses.SettlementView.class,
                transactionId -> retry.execute("settle", () -> views.settlement(
                        settlements.settle(id, body.settledAt(), transactionId)))));
    }

    @PostMapping("/{id}/release")
    @Operation(summary = "Cancel an authorization and release the hold",
            description = "Reverses the hold transaction so the customer's funds become spendable again.")
    public ResponseEntity<Responses.AuthorizationView> release(
            @RequestHeader("Idempotency-Key") String key,
            @PathVariable UUID id,
            @RequestBody(required = false) Requests.ReleaseRequest request) {
        Requests.ReleaseRequest body = request == null ? new Requests.ReleaseRequest(null, null) : request;
        String reason = body.reason() == null ? "released by request" : body.reason();
        return respond(idempotency.execute("authorizations.release." + id, key, body,
                Responses.AuthorizationView.class,
                transactionId -> retry.execute("release", () -> views.authorization(
                        authorizations.release(id, reason, body.occurredAt())))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read one authorization")
    public Responses.AuthorizationView get(@PathVariable UUID id) {
        return views.authorization(authorizations.require(id));
    }

    @GetMapping
    @Operation(summary = "List open authorizations, or one customer's history")
    public List<Responses.AuthorizationView> list(
            @RequestParam(required = false) String customerId,
            @RequestParam(defaultValue = "100") int limit) {
        List<com.tribule.ledger.settlement.FxAuthorization> found = customerId == null
                ? authorizations.pending()
                : authorizations.forCustomer(customerId, limit);
        return found.stream().map(views::authorization).toList();
    }

    private <T> ResponseEntity<T> respond(IdempotencyService.Outcome<T> outcome) {
        return ResponseEntity.ok()
                .header("Idempotent-Replay", Boolean.toString(outcome.replayed()))
                .body(outcome.value());
    }
}
