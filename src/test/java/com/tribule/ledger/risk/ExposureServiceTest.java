package com.tribule.ledger.risk;

import com.tribule.ledger.settlement.AuthorizationService;
import com.tribule.ledger.settlement.SettlementService;
import com.tribule.ledger.support.AbstractLedgerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settlement risk, and the claim that makes it trustworthy.
 *
 * <p>Exposure is not a second model sitting next to the ledger. It is the settlement
 * calculation with the current rate substituted for the settlement rate:
 *
 * <pre>
 *   settlement:  realized   = convert(sellAmount, rate at settlement) - quotedAmount
 *   exposure:    unrealized = convert(sellAmount, rate now)           - quotedAmount
 * </pre>
 *
 * <p>So the headline test here is that an authorization's unrealized P&amp;L at a given
 * rate equals its realized P&amp;L when it settles at that same rate. If those two could
 * disagree, the risk report would be decoration.
 *
 * <p>These tests use CHF/SGD, a pair no other test touches, because the exposure
 * report is global by nature and shares a database with everything else.
 */
class ExposureServiceTest extends AbstractLedgerTest {

    @Autowired private ExposureService exposure;
    @Autowired private AuthorizationService authorizations;
    @Autowired private SettlementService settlements;

    @Test
    @DisplayName("unrealized P&L at a rate equals realized P&L when it settles at that rate")
    void unrealizedBecomesRealized() {
        Instant t0 = nextTimeline();
        Instant t1 = t0.plus(Duration.ofDays(1));
        publishRate("CHF", "SGD", "1.50", t0);
        publishRate("SGD", "USD", "0.74", t0);

        String customer = newCustomer("exposure");
        fund(customer, "CHF", 100_000);
        AuthorizationService.AuthorizationResult authorized = authorizations.authorize(
                new AuthorizationService.AuthorizeCommand(newReference("exposure"), customer, "CHF", "SGD",
                        100_000, t0.plusSeconds(60), Duration.ofDays(30)),
                UUID.randomUUID());
        assertThat(authorized.authorization().quotedBuyAmountMinor()).isEqualTo(150_000);

        // The rate moves. Nothing has settled, so this is exposure, not result.
        publishRate("CHF", "SGD", "1.55", t1);
        Instant asOf = t1.plusSeconds(60);

        OpenPosition position = positionFor(exposure.report(asOf), "CHF/SGD").orElseThrow();
        assertThat(position.markedBuyNotionalMinor()).isEqualTo(155_000);
        assertThat(position.quotedBuyNotionalMinor()).isEqualTo(150_000);
        assertThat(position.unrealizedPnlMinor()).isEqualTo(5_000);
        assertThat(position.currentRate()).isEqualByComparingTo("1.55");
        assertThat(position.oldestAgeSeconds()).isPositive();

        // Settle at that same rate and the unrealized figure becomes the realized one.
        SettlementService.SettlementResult settled = settlements.settle(
                authorized.authorization().id(), asOf, UUID.randomUUID());

        assertThat(settled.realizedPnl().minorUnits())
                .as("the risk report and the books must be the same calculation")
                .isEqualTo(position.unrealizedPnlMinor());

        // And the position is gone, because the risk is no longer open.
        assertThat(positionFor(exposure.report(asOf.plusSeconds(1)), "CHF/SGD")).isEmpty();
        assertBooksBalance();
    }

    @Test
    @DisplayName("an adverse move shows as a negative position before it is ever booked")
    void adverseMoveShowsAsUnrealizedLoss() {
        Instant t0 = nextTimeline();
        Instant t1 = t0.plus(Duration.ofDays(1));
        publishRate("CHF", "JPY", "170.00", t0);
        publishRate("JPY", "USD", "0.0063", t0);

        String customer = newCustomer("adverse");
        fund(customer, "CHF", 200_000);
        authorizations.authorize(new AuthorizationService.AuthorizeCommand(
                        newReference("adverse"), customer, "CHF", "JPY", 200_000,
                        t0.plusSeconds(60), Duration.ofDays(30)),
                UUID.randomUUID());

        // 2000.00 CHF at 170 is 340,000 JPY, and JPY has no minor units at all.
        publishRate("CHF", "JPY", "160.00", t1);
        OpenPosition position = positionFor(exposure.report(t1.plusSeconds(60)), "CHF/JPY").orElseThrow();

        assertThat(position.quotedBuyNotionalMinor()).isEqualTo(340_000);
        assertThat(position.markedBuyNotionalMinor()).isEqualTo(320_000);
        assertThat(position.unrealizedPnlMinor()).isEqualTo(-20_000);
        assertThat(position.isLoss()).isTrue();
        // Reported in the reporting currency too, so positions across pairs can be added up.
        assertThat(position.unrealizedPnlReportingMinor()).isNegative();
    }

    @Test
    @DisplayName("net currency position is long what we are owed and short what we promised")
    void netPositionDirectionIsCorrect() {
        Instant t0 = nextTimeline();
        publishRate("KWD", "EUR", "3.20", t0);
        publishRate("KWD", "USD", "3.26", t0);
        publishRate("EUR", "USD", "1.08", t0);
        Instant asOf = t0.plusSeconds(120);

        // Deltas, not absolutes: the report is global by nature and other tests leave
        // positions behind in the same database.
        long kwdBefore = netFor(exposure.report(asOf), "KWD");
        long eurBefore = netFor(exposure.report(asOf), "EUR");

        String customer = newCustomer("net");
        fund(customer, "KWD", 60_000);
        authorizations.authorize(new AuthorizationService.AuthorizeCommand(
                        newReference("net"), customer, "KWD", "EUR", 60_000,
                        t0.plusSeconds(60), Duration.ofDays(30)),
                UUID.randomUUID());

        ExposureReport report = exposure.report(asOf);

        // 60.000 KWD (scale 3) at 3.20 is 192.00 EUR (scale 2).
        assertThat(netFor(report, "KWD") - kwdBefore)
                .as("we are owed KWD, so the position is long")
                .isEqualTo(60_000);
        assertThat(netFor(report, "EUR") - eurBefore)
                .as("we promised EUR we do not hold yet, so the position is short")
                .isEqualTo(-19_200);
        assertThat(report.grossExposureReportingMinor()).isPositive();
    }

    @Test
    @DisplayName("value at risk is computed from real rate history, and says so when there is none")
    void valueAtRiskUsesHistory() {
        Instant t0 = nextTimeline();
        publishRate("SGD", "USD", "0.74", t0);

        // Forty-five daily observations of a pair that drifts and wobbles.
        BigDecimal rate = new BigDecimal("1.7000");
        for (int day = 0; day < 45; day++) {
            BigDecimal step = BigDecimal.valueOf(((day * 37) % 11) - 5)
                    .multiply(new BigDecimal("0.0015"));
            rate = rate.add(step).setScale(6, RoundingMode.HALF_EVEN);
            publishRate("GBP", "SGD", rate.toPlainString(), t0.plus(Duration.ofDays(day)));
        }
        Instant asOf = t0.plus(Duration.ofDays(45));

        String customer = newCustomer("var");
        fund(customer, "GBP", 500_000);
        authorizations.authorize(new AuthorizationService.AuthorizeCommand(
                        newReference("var"), customer, "GBP", "SGD", 500_000,
                        t0.plus(Duration.ofDays(44)), Duration.ofDays(30)),
                UUID.randomUUID());

        ExposureReport report = exposure.report(asOf);
        VarEstimate estimate = report.valueAtRisk().stream()
                .filter(v -> v.pair().equals("GBP/SGD"))
                .findFirst()
                .orElseThrow();

        assertThat(estimate.sufficientData()).isTrue();
        assertThat(estimate.observations()).isGreaterThanOrEqualTo(10);
        assertThat(estimate.worstReturn()).isNegative();
        assertThat(estimate.varReportingMinor()).isPositive();
        assertThat(estimate.confidence()).isEqualTo(0.95);
        // The limits of the method travel with the number.
        assertThat(estimate.method()).contains("historical simulation");
        assertThat(report.notes()).anyMatch(note -> note.contains("no correlation benefit"));
    }

    @Test
    @DisplayName("too little history produces no number rather than a meaningless one")
    void insufficientHistoryIsReportedHonestly() {
        Instant t0 = nextTimeline();
        publishRate("KWD", "JPY", "500.0", t0);
        publishRate("JPY", "USD", "0.0063", t0);

        String customer = newCustomer("thin-history");
        fund(customer, "KWD", 10_000);
        authorizations.authorize(new AuthorizationService.AuthorizeCommand(
                        newReference("thin"), customer, "KWD", "JPY", 10_000,
                        t0.plusSeconds(60), Duration.ofDays(30)),
                UUID.randomUUID());

        VarEstimate estimate = exposure.report(t0.plusSeconds(120)).valueAtRisk().stream()
                .filter(v -> v.pair().equals("KWD/JPY"))
                .findFirst()
                .orElseThrow();

        assertThat(estimate.sufficientData()).isFalse();
        assertThat(estimate.varReportingMinor()).isZero();
        assertThat(estimate.method()).contains("not enough rate history");
    }

    private Optional<OpenPosition> positionFor(ExposureReport report, String pair) {
        return report.positions().stream().filter(p -> p.pair().equals(pair)).findFirst();
    }

    private long netFor(ExposureReport report, String currency) {
        return report.netByCurrency().stream()
                .filter(c -> c.currency().equals(currency))
                .mapToLong(CurrencyExposure::netPositionMinor)
                .findFirst()
                .orElse(0L);
    }
}
