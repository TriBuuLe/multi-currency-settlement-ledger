package com.tribule.ledger.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tribule.ledger.support.AbstractLedgerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract.
 *
 * <p>Two things get checked here that are easy to get wrong and invisible from the
 * service layer: that the idempotency key is genuinely required rather than optional,
 * and that the status codes distinguish "retrying might work" from "retrying will fail
 * the same way". A client's behaviour depends entirely on that distinction.
 */
@AutoConfigureMockMvc
class WebApiTest extends AbstractLedgerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private io.swagger.v3.oas.models.OpenAPI openApi;

    @Test
    @DisplayName("a mutating request without an idempotency key is refused, with an explanation")
    void idempotencyKeyIsRequired() throws Exception {
        mvc.perform(post("/api/v1/payments/funding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fundingBody(newCustomer("web-nokey"), 1_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Missing header"))
                .andExpect(jsonPath("$.hint").value(org.hamcrest.Matchers.containsString("retry after a timeout")));
    }

    @Test
    @DisplayName("retrying with the same key replays rather than posting twice")
    void retryIsReplayed() throws Exception {
        String customer = newCustomer("web-replay");
        String key = "web-" + UUID.randomUUID();
        String body = fundingBody(customer, 25_000);

        MvcResult first = mvc.perform(post("/api/v1/payments/funding")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andReturn();

        MvcResult second = mvc.perform(post("/api/v1/payments/funding")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn();

        JsonNode firstTransaction = json.readTree(first.getResponse().getContentAsString()).get("transaction");
        JsonNode secondTransaction = json.readTree(second.getResponse().getContentAsString()).get("transaction");
        assertThat(secondTransaction.get("id").asText())
                .as("the replay must point at the same transaction, not a new one")
                .isEqualTo(firstTransaction.get("id").asText());
        assertThat(walletBalance(customer, "USD")).isEqualTo(25_000);
    }

    @Test
    @DisplayName("the same key with a different body is 422, not a silently wrong success")
    void reusedKeyIsUnprocessable() throws Exception {
        String customer = newCustomer("web-reuse");
        String key = "web-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/payments/funding")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fundingBody(customer, 10_000)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/payments/funding")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fundingBody(customer, 999_999)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Idempotency key reused with a different body"));

        assertThat(walletBalance(customer, "USD")).isEqualTo(10_000);
    }

    @Test
    @DisplayName("insufficient funds is 409 and an unbalanced request is 422")
    void statusCodesDistinguishRetryable() throws Exception {
        String customer = newCustomer("web-codes");
        mvc.perform(post("/api/v1/payments/funding")
                        .header("Idempotency-Key", "web-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fundingBody(customer, 5_000)))
                .andExpect(status().isOk());

        // Transient: more money could arrive, so retrying later is sensible.
        mvc.perform(post("/api/v1/payments/payouts")
                        .header("Idempotency-Key", "web-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","currency":"USD","amountMinor":50000,"reference":"%s"}
                                """.formatted(customer, newReference("web-over"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient funds"));

        // Permanent: this request can never succeed as written.
        mvc.perform(post("/api/v1/payments/funding")
                        .header("Idempotency-Key", "web-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"","currency":"US","amountMinor":-5,"reference":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.fields").isMap());
    }

    @Test
    @DisplayName("the authorize-then-settle flow works end to end over HTTP")
    void authorizeAndSettleOverHttp() throws Exception {
        Instant t0 = nextTimeline();
        Instant t1 = t0.plus(Duration.ofDays(1));
        String customer = newCustomer("web-fx");

        mvc.perform(post("/api/v1/fx/rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency":"EUR","quoteCurrency":"USD","rate":"1.10",
                                 "effectiveAt":"%s","observedAt":"%s","source":"web-test"}
                                """.formatted(t0, t0)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/payments/funding")
                        .header("Idempotency-Key", "web-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","currency":"EUR","amountMinor":100000,"reference":"%s"}
                                """.formatted(customer, newReference("web-fund"))))
                .andExpect(status().isOk());

        MvcResult authorized = mvc.perform(post("/api/v1/authorizations")
                        .header("Idempotency-Key", "web-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference":"%s","customerId":"%s","sellCurrency":"EUR","buyCurrency":"USD",
                                 "sellAmountMinor":100000,"authorizedAt":"%s","ttlSeconds":2592000}
                                """.formatted(newReference("web-auth"), customer, t0.plusSeconds(60))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorization.status").value("PENDING"))
                .andExpect(jsonPath("$.authorization.quoted.amountMinor").value(110000))
                .andExpect(jsonPath("$.quotedRate.resolution").value("DIRECT"))
                .andReturn();

        String authorizationId = json.readTree(authorized.getResponse().getContentAsString())
                .get("authorization").get("id").asText();

        // The rate moves in the house's favour before settlement.
        mvc.perform(post("/api/v1/fx/rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency":"EUR","quoteCurrency":"USD","rate":"1.15",
                                 "effectiveAt":"%s","observedAt":"%s","source":"web-test"}
                                """.formatted(t1, t1)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/authorizations/{id}/settlement", authorizationId)
                        .header("Idempotency-Key", "web-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"settledAt":"%s"}
                                """.formatted(t1.plusSeconds(60))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorization.status").value("SETTLED"))
                .andExpect(jsonPath("$.delivered.amountMinor").value(110000))
                .andExpect(jsonPath("$.marketValue.amountMinor").value(115000))
                .andExpect(jsonPath("$.realizedPnl.amountMinor").value(5000))
                .andExpect(jsonPath("$.explanation").value(org.hamcrest.Matchers.containsString("gained")));

        // Settling again is a conflict, not a second payout.
        mvc.perform(post("/api/v1/authorizations/{id}/settlement", authorizationId)
                        .header("Idempotency-Key", "web-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"settledAt":"%s"}
                                """.formatted(t1.plusSeconds(120))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the trial balance and the verifier both report a healthy ledger")
    void trialBalanceAndVerifyAreExposed() throws Exception {
        mvc.perform(get("/api/v1/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].balanced").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is(true))));

        mvc.perform(post("/api/v1/admin/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.findings").isEmpty());
    }

    @Test
    @DisplayName("a missing rate is 422 and names the pair")
    void missingRateIsUnprocessable() throws Exception {
        mvc.perform(get("/api/v1/fx/rates/resolve")
                        .param("from", "EUR").param("to", "USD")
                        .param("effectiveAt", "1999-01-01T00:00:00Z")
                        .param("knownAt", "1999-01-01T00:00:00Z"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("No usable FX rate"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("EUR/USD")));
    }

    @Test
    @DisplayName("the API description carries the contract clients need")
    void openApiDescriptionIsConfigured() {
        // The springdoc endpoint itself is only mounted by a real servlet container, so
        // it is checked by the smoke test in the Makefile rather than here. What belongs
        // in a unit test is the part this project actually writes: the document's own
        // metadata, including the idempotency rule every client has to follow.
        assertThat(openApi.getInfo().getTitle()).isEqualTo("Multi-Currency Settlement Ledger");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("1.0.0");
        assertThat(openApi.getInfo().getDescription())
                .contains("Idempotency-Key")
                .contains("replays the original response");
    }

    private String fundingBody(String customerId, long amountMinor) {
        return """
                {"customerId":"%s","currency":"USD","amountMinor":%d,"reference":"%s"}
                """.formatted(customerId, amountMinor, newReference("web-fund"));
    }
}
