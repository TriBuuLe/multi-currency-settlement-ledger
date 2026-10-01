package com.tribule.ledger.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Inbound payloads.
 *
 * <p>Amounts are always minor units, never decimal strings. An API that accepts
 * "10.00" has to decide what that means in JPY and in KWD, and the answer is
 * different; accepting an integer count of minor units moves that decision to the
 * one place that knows the currency's scale.
 *
 * <p>Timestamps are optional and default to now. When supplied they set the
 * transaction's valid time, which is what makes backdating expressible.
 */
public final class Requests {

    private Requests() {
    }

    public record FundRequest(
            @NotBlank String customerId,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Positive long amountMinor,
            @NotBlank String reference,
            Instant occurredAt) {
    }

    public record PayoutRequest(
            @NotBlank String customerId,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Positive long amountMinor,
            @NotBlank String reference,
            Instant occurredAt) {
    }

    public record TransferRequest(
            @NotBlank String fromCustomerId,
            @NotBlank String toCustomerId,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Positive long amountMinor,
            @NotBlank String reference,
            Instant occurredAt) {
    }

    public record ConvertRequest(
            @NotBlank String customerId,
            @NotBlank @Size(min = 3, max = 3) String sellCurrency,
            @NotBlank @Size(min = 3, max = 3) String buyCurrency,
            @Positive long sellAmountMinor,
            @NotBlank String reference,
            Instant occurredAt) {
    }

    public record AuthorizeRequest(
            @NotBlank String reference,
            @NotBlank String customerId,
            @NotBlank @Size(min = 3, max = 3) String sellCurrency,
            @NotBlank @Size(min = 3, max = 3) String buyCurrency,
            @Positive long sellAmountMinor,
            Instant authorizedAt,
            /** How long the quote is held. Falls back to the configured default. */
            Long ttlSeconds) {
    }

    public record SettleRequest(Instant settledAt) {
    }

    public record ReleaseRequest(String reason, Instant occurredAt) {
    }

    public record ReverseRequest(@NotBlank String reason, Instant occurredAt) {
    }

    public record PublishRateRequest(
            @NotBlank @Size(min = 3, max = 3) String baseCurrency,
            @NotBlank @Size(min = 3, max = 3) String quoteCurrency,
            @NotNull @Positive BigDecimal rate,
            @NotNull Instant effectiveAt,
            /** When we learned it. Defaults to now; set it explicitly to backfill history. */
            Instant observedAt,
            String source) {
    }

    public record CorrectRateRequest(
            @NotNull UUID supersededRateId,
            @NotNull @Positive BigDecimal rate,
            Instant observedAt,
            String source) {
    }

    public record ImportStatementRequest(
            @NotBlank String source,
            @NotBlank String filename,
            @NotNull LocalDate asOfDate,
            /** The statement itself: CSV with an external_ref header. */
            @NotBlank String csv) {
    }
}
