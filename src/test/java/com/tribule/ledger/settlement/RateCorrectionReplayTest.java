package com.tribule.ledger.settlement;

import com.tribule.ledger.fx.FxRate;
import com.tribule.ledger.ledger.ChartOfAccounts;
import com.tribule.ledger.support.AbstractLedgerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Restating a settlement after the vendor admits the rate was wrong.
 *
 * <p>The point is how little special machinery this needs. Replay re-runs the ordinary
 * settlement arithmetic with the knowledge time moved forward, and the corrected
 * observation comes back instead of the wrong one. No parallel correction code path,
 * no editing of history.
 */
class RateCorrectionReplayTest extends AbstractLedgerTest {

    @Autowired private AuthorizationService authorizations;
    @Autowired private SettlementService settlements;
    @Autowired private RateCorrectionReplayService replay;

    @Test
    @DisplayName("replay restates the house's P&L and leaves the customer alone")
    void replayRestatesHousePnlOnly() {
        Instant t0 = nextTimeline();
        Instant t1 = t0.plus(Duration.ofDays(1));
        Instant t2 = t0.plus(Duration.ofDays(2));

        publishRate("EUR", "USD", "1.10", t0);
        String customer = newCustomer("replay");
        fund(customer, "EUR", 100_000);

        AuthorizationService.AuthorizationResult authorized = authorizations.authorize(
                new AuthorizationService.AuthorizeCommand(newReference("replay"), customer, "EUR", "USD",
                        100_000, t0.plusSeconds(60), Duration.ofDays(30)),
                UUID.randomUUID());
        assertThat(authorized.authorization().quotedBuyAmountMinor()).isEqualTo(110_000);

        // Settle against a rate that turns out to be wrong.
        FxRate badTick = fx.publish("EUR", "USD", new BigDecimal("1.15"), t1, t1, "vendor");
        SettlementService.SettlementResult settled = settlements.settle(
                authorized.authorization().id(), t1.plusSeconds(60), UUID.randomUUID());
        assertThat(settled.realizedPnl().minorUnits()).isEqualTo(5_000);

        long walletAfterSettlement = walletBalance(customer, "USD");
        long pnlAfterSettlement = houseBalance(ChartOfAccounts.fxRealizedPnl("USD"));
        long positionAfterSettlement = houseBalance(ChartOfAccounts.fxPosition("USD"));

        // The vendor corrects the tick: it was really 1.16.
        FxRate correction = fx.publishCorrection(badTick.id(), new BigDecimal("1.16"), t2, "vendor-fix");

        RateCorrectionReplayService.ReplayResult result = replay.replay(correction.id(), t2.plusSeconds(1));

        assertThat(result.settlementsExamined()).isEqualTo(1);
        assertThat(result.adjustmentsPosted()).isEqualTo(1);
        assertThat(result.netPnlDeltaMinor()).isEqualTo(1_000);

        RateCorrectionReplayService.Adjustment adjustment = result.adjustments().getFirst();
        assertThat(adjustment.originalPnlMinor()).isEqualTo(5_000);
        assertThat(adjustment.correctedPnlMinor()).isEqualTo(6_000);
        assertThat(adjustment.pnlDeltaMinor()).isEqualTo(1_000);

        // The customer was quoted 1.10 and paid 1.10. A vendor's bad tick is not their problem.
        assertThat(walletBalance(customer, "USD"))
                .as("customer balances must not move when the house restates its own books")
                .isEqualTo(walletAfterSettlement);
        // The house's own accounts absorb the restatement.
        assertThat(houseBalance(ChartOfAccounts.fxRealizedPnl("USD")) - pnlAfterSettlement).isEqualTo(1_000);
        assertThat(houseBalance(ChartOfAccounts.fxPosition("USD")) - positionAfterSettlement).isEqualTo(-1_000);
        assertBooksBalance();
    }

    @Test
    @DisplayName("the original settlement is left intact beside its adjustment")
    void originalSettlementIsNotEdited() {
        Instant t0 = nextTimeline();
        Instant t1 = t0.plus(Duration.ofDays(1));
        publishRate("GBP", "USD", "1.25", t0);

        String customer = newCustomer("intact");
        fund(customer, "GBP", 40_000);
        AuthorizationService.AuthorizationResult authorized = authorizations.authorize(
                new AuthorizationService.AuthorizeCommand(newReference("intact"), customer, "GBP", "USD",
                        40_000, t0.plusSeconds(60), Duration.ofDays(30)),
                UUID.randomUUID());

        FxRate badTick = fx.publish("GBP", "USD", new BigDecimal("1.30"), t1, t1, "vendor");
        SettlementService.SettlementResult settled = settlements.settle(
                authorized.authorization().id(), t1.plusSeconds(60), UUID.randomUUID());
        UUID settlementTransactionId = settled.settlementTransaction().transaction().id();
        int originalLegCount = settled.settlementTransaction().entries().size();

        FxRate correction = fx.publishCorrection(
                badTick.id(), new BigDecimal("1.33"), t1.plus(Duration.ofDays(1)), "vendor-fix");
        RateCorrectionReplayService.ReplayResult result = replay.replay(correction.id(), null);

        // The settlement is byte-for-byte what it was when it was booked.
        assertThat(ledger.require(settlementTransactionId).entries()).hasSize(originalLegCount);
        // And the correction sits next to it as its own transaction.
        UUID adjustmentId = result.adjustments().getFirst().adjustmentTransactionId();
        assertThat(ledger.require(adjustmentId).transaction().kind())
                .isEqualTo(com.tribule.ledger.ledger.TransactionKind.RATE_CORRECTION_ADJUSTMENT);
        assertThat(adjustmentId).isNotEqualTo(settlementTransactionId);
        assertBooksBalance();
    }

    @Test
    @DisplayName("a correction that changes nothing posts nothing")
    void noOpCorrectionPostsNothing() {
        Instant t0 = nextTimeline();
        Instant t1 = t0.plus(Duration.ofDays(1));
        publishRate("CHF", "USD", "1.12", t0);

        String customer = newCustomer("noop");
        fund(customer, "CHF", 10_000);
        AuthorizationService.AuthorizationResult authorized = authorizations.authorize(
                new AuthorizationService.AuthorizeCommand(newReference("noop"), customer, "CHF", "USD",
                        10_000, t0.plusSeconds(60), Duration.ofDays(30)),
                UUID.randomUUID());

        FxRate tick = fx.publish("CHF", "USD", new BigDecimal("1.15"), t1, t1, "vendor");
        settlements.settle(authorized.authorization().id(), t1.plusSeconds(60), UUID.randomUUID());

        // A correction too small to change a single minor unit of the booked amount.
        FxRate correction = fx.publishCorrection(
                tick.id(), new BigDecimal("1.1500001"), t1.plus(Duration.ofDays(1)), "rounding-only");
        RateCorrectionReplayService.ReplayResult result = replay.replay(correction.id(), null);

        assertThat(result.settlementsExamined()).isEqualTo(1);
        assertThat(result.adjustmentsPosted())
                .as("a correction below one minor unit must not generate noise entries")
                .isZero();
        assertThat(result.notes()).anyMatch(note -> note.contains("needed no adjustment"));
    }

    @Test
    @DisplayName("replaying something that is not a correction is rejected")
    void onlyCorrectionsCanBeReplayed() {
        Instant t0 = nextTimeline();
        FxRate plainRate = fx.publish("EUR", "JPY", new BigDecimal("170.5"), t0, t0, "vendor");

        assertThatThrownBy(() -> replay.replay(plainRate.id(), null))
                .isInstanceOf(com.tribule.ledger.fx.FxException.class)
                .hasMessageContaining("supersedes nothing");
    }
}
