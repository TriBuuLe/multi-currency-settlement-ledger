package com.tribule.ledger.web;

import com.tribule.ledger.idempotency.IdempotencyService;
import com.tribule.ledger.ledger.TransactionalRetry;
import com.tribule.ledger.payments.TransferService;
import com.tribule.ledger.web.dto.Requests;
import com.tribule.ledger.web.dto.Responses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Funding, payouts, transfers, and immediate conversion")
public class PaymentsController {

    private final TransferService transfers;
    private final IdempotencyService idempotency;
    private final TransactionalRetry retry;
    private final ViewMapper views;

    public PaymentsController(TransferService transfers, IdempotencyService idempotency,
                             TransactionalRetry retry, ViewMapper views) {
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.retry = retry;
        this.views = views;
    }

    @PostMapping("/funding")
    @Operation(summary = "Record money arriving from outside",
            description = "Debits the nostro account and credits the customer's wallet.")
    public ResponseEntity<Responses.TransferView> fund(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Requests.FundRequest request) {
        return respond(idempotency.execute("payments.funding", key, request, Responses.TransferView.class,
                transactionId -> retry.execute("funding", () -> {
                    TransferService.TransferResult result = transfers.fund(
                            request.customerId(), request.currency(), request.amountMinor(),
                            request.reference(), request.occurredAt(), transactionId);
                    return new Responses.TransferView(
                            views.transaction(result.transaction()), views.money(result.amount()));
                })));
    }

    @PostMapping("/payouts")
    @Operation(summary = "Send money out of the system")
    public ResponseEntity<Responses.TransferView> payout(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Requests.PayoutRequest request) {
        return respond(idempotency.execute("payments.payout", key, request, Responses.TransferView.class,
                transactionId -> retry.execute("payout", () -> {
                    TransferService.TransferResult result = transfers.payout(
                            request.customerId(), request.currency(), request.amountMinor(),
                            request.reference(), request.occurredAt(), transactionId);
                    return new Responses.TransferView(
                            views.transaction(result.transaction()), views.money(result.amount()));
                })));
    }

    @PostMapping("/transfers")
    @Operation(summary = "Move money between two customers in the same currency")
    public ResponseEntity<Responses.TransferView> transfer(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Requests.TransferRequest request) {
        return respond(idempotency.execute("payments.transfer", key, request, Responses.TransferView.class,
                transactionId -> retry.execute("transfer", () -> {
                    TransferService.TransferResult result = transfers.transfer(
                            request.fromCustomerId(), request.toCustomerId(), request.currency(),
                            request.amountMinor(), request.reference(), request.occurredAt(), transactionId);
                    return new Responses.TransferView(
                            views.transaction(result.transaction()), views.money(result.amount()));
                })));
    }

    @PostMapping("/conversions")
    @Operation(summary = "Convert one of a customer's balances at the current rate",
            description = """
                    Settles immediately, so it carries no settlement risk -- the contrast to an
                    authorization. When the pair is not quoted directly the rate is composed through the
                    pivot currency and the resulting sub-unit residual is posted to the FX rounding
                    account, which is reported as `roundingResidual`.
                    """)
    public ResponseEntity<Responses.ConversionView> convert(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Requests.ConvertRequest request) {
        return respond(idempotency.execute("payments.conversion", key, request, Responses.ConversionView.class,
                transactionId -> retry.execute("conversion", () -> {
                    TransferService.ConversionResult result = transfers.convert(
                            request.customerId(), request.sellCurrency(), request.buyCurrency(),
                            request.sellAmountMinor(), request.reference(), request.occurredAt(), transactionId);
                    return new Responses.ConversionView(
                            views.transaction(result.transaction()),
                            views.money(result.sold()),
                            views.money(result.bought()),
                            views.money(result.roundingResidual()),
                            views.rate(result.rate()));
                })));
    }

    private <T> ResponseEntity<T> respond(IdempotencyService.Outcome<T> outcome) {
        return ResponseEntity.ok()
                .header("Idempotent-Replay", Boolean.toString(outcome.replayed()))
                .body(outcome.value());
    }
}
