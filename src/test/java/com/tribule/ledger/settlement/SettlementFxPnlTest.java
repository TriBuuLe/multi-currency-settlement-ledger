package com.tribule.ledger.settlement;

import com.tribule.ledger.ledger.ChartOfAccounts;
import com.tribule.ledger.support.AbstractLedgerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Authorization, settlement, and where the rate move ends up.
 *
 * <p>The thing being proved here is that a transaction can move two currencies and
 * still balance in each one independently, and that the difference between the
 * quoted rate and the settlement rate lands in an account somebody can read -- not
 * buried in a customer balance and not rounded away.
 */
class SettlementFxPnlTest extends AbstractLedgerTest {

    @Autowired private AuthorizationService authorizations;
    @Autowired private SettlementService settlements;

    @Test
    @DisplayName("authorizing reserves the customer's funds and records the obligation off balance sheet")
    void authorizationHoldsFundsAndRecordsCommitment() {
        Instant t0 = nextTimeline();
        publishRate("EUR", "USD", "1.10", t0);

        String customer = newCustomer("auth");
        fund(customer, "EUR", 100_000);
        long commitmentBefore = houseBalance(ChartOfAccounts.fxCommitment("USD"));

        AuthorizationService.AuthorizationResult result = authorize(customer, "EUR", "USD", 100_000, t0);

        assertThat(result.authorization().quotedBuyAmountMinor()).isEqualTo(110_000);
        assertThat(result.authorization().status()).isEqualTo(AuthorizationStatus.PENDING);

        // Real money moved out of spendable and into held: the customer cannot spend it twice.
        assertThat(walletBalance(customer, "EUR")).isZero();
        assertThat(heldBalance(customer, "EUR")).isEqualTo(100_000);
        // No USD has moved -- the obligation is recorded, not settled.
        assertThat(walletBalance(customer, "USD")).isZero();
        assertThat(houseBalance(ChartOfAccounts.fxCommitment("USD")) - commitmentBefore).isEqualTo(110_000);
        assertBooksBalance();
    }

    @Test
    @DisplayName("a favourable rate move is realized as a gain in a dedicated account")
    void favourableMoveBecomesRealizedGain() {
        Instant t0 = nextTimeline();
        Instant t1 = t0.plus(Duration.ofDays(1));
        publishRate("EUR", "USD", "1.10", t0);

        String customer = newCustomer("gain");
        fund(customer, "EUR", 100_000);
        AuthorizationService.AuthorizationResult authorized = authorize(customer, "EUR", "USD", 100_000, t0);

        // The market moves our way between authorization and settlement.
        publishRate("EUR", "USD", "1.15", t1);
        long pnlBefore = houseBalance(ChartOfAccounts.fxRealizedPnl("USD"));

        SettlementService.SettlementResult settled = settlements.settle(
                authorized.authorization().id(), t1.plusSeconds(60), UUID.randomUUID());

        // The customer gets exactly what they were quoted. Honouring the quote is the product.
        assertThat(settled.delivered().minorUnits()).isEqualTo(110_000);
        assertThat(walletBalance(customer, "USD")).isEqualTo(110_000);
        // The market delivered more, and the difference is the house's.
        assertThat(settled.marketValue().minorUnits()).isEqualTo(115_000);
        assertThat(settled.realizedPnl().minorUnits()).isEqualTo(5_000);
        assertThat(houseBalance(ChartOfAccounts.fxRealizedPnl("USD")) - pnlBefore).isEqualTo(5_000);

        // The hold and the commitment are both discharged.
        assertThat(heldBalance(customer, "EUR")).isZero();
        assertThat(settled.authorization().status()).isEqualTo(AuthorizationStatus.SETTLED);
        assertBooksBalance();
    }

    @Test
    @DisplayName("an adverse rate move is absorbed by the house, not passed to the customer")
    void adverseMoveBecomesRealizedLoss() {
        Instant t0 = nextTimeline();
        Instant t1 = t0.plus(Duration.ofDays(1));
        publishRate("EUR", "USD", "1.10", t0);

        String customer = newCustomer("loss");
        fund(customer, "EUR", 100_000);
        AuthorizationService.AuthorizationResult authorized = authorize(customer, "EUR", "USD", 100_000, t0);

        publishRate("EUR", "USD", "1.05", t1);
        long pnlBefore = houseBalance(ChartOfAccounts.fxRealizedPnl("USD"));

        SettlementService.SettlementResult settled = settlements.settle(
                authorized.authorization().id(), t1.plusSeconds(60), UUID.randomUUID());

        // The customer is unaffected: they were quoted 1.10 and they get 1.10.
        assertThat(walletBalance(customer, "USD")).isEqualTo(110_000);
        assertThat(settled.marketValue().minorUnits()).isEqualTo(105_000);
        // The loss is visible, signed, and in its own account -- this is the number a
        // pricing desk needs to know the spread was too thin.
        assertThat(settled.realizedPnl().minorUnits()).isEqualTo(-5_000);
        assertThat(houseBalance(ChartOfAccounts.fxRealizedPnl("USD")) - pnlBefore).isEqualTo(-5_000);
        assertBooksBalance();
    }

    @Test
    @DisplayName("an unchanged rate realizes nothing and posts no P&L leg at all")
    void flatRateRealizesNothing() {
        Instant t0 = nextTimeline();
        publishRate("GBP", "USD", "1.27", t0);

        String customer = newCustomer("flat");
        fund(customer, "GBP", 50_000);
        AuthorizationService.AuthorizationResult authorized = authorize(customer, "GBP", "USD", 50_000, t0);

        SettlementService.SettlementResult settled = settlements.settle(
                authorized.authorization().id(), t0.plus(Duration.ofHours(1)), UUID.randomUUID());

        assertThat(settled.realizedPnl().isZero()).isTrue();
        // signed() drops zero-value legs rather than posting meaningless zeros.
        assertThat(settled.settlementTransaction().entries())
                .noneMatch(e -> e.accountCode().equals(ChartOfAccounts.fxRealizedPnl("USD")));
        assertBooksBalance();
    }

    @Test
    @DisplayName("a settlement transaction balances in both currencies independently")
    void settlementBalancesPerCurrency() {
        Instant t0 = nextTimeline();
        Instant t1 = t0.plus(Duration.ofDays(1));
        publishRate("EUR", "USD", "1.10", t0);

        String customer = newCustomer("per-currency");
        fund(customer, "EUR", 77_777);
        AuthorizationService.AuthorizationResult authorized = authorize(customer, "EUR", "USD", 77_777, t0);
        publishRate("EUR", "USD", "1.13", t1);

        SettlementService.SettlementResult settled = settlements.settle(
                authorized.authorization().id(), t1.plusSeconds(1), UUID.randomUUID());

        // The real claim: not that the transaction balances overall, but that each
        // currency balances on its own. A cross-currency total would be meaningless.
        long eurNet = settled.settlementTransaction().entries().stream()
                .filter(e -> e.currencyCode().equals("EUR"))
                .mapToLong(e -> e.signedAmountMinor()).sum();
        long usdNet = settled.settlementTransaction().entries().stream()
                .filter(e -> e.currencyCode().equals("USD"))
                .mapToLong(e -> e.signedAmountMinor()).sum();

        assertThat(eurNet).isZero();
        assertThat(usdNet).isZero();
        assertBooksBalance();
    }

    @Test
    @DisplayName("an authorization cannot be settled twice")
    void settlementIsOnlyPossibleOnce() {
        Instant t0 = nextTimeline();
        publishRate("CHF", "USD", "1.12", t0);
        String customer = newCustomer("double-settle");
        fund(customer, "CHF", 20_000);
        AuthorizationService.AuthorizationResult authorized = authorize(customer, "CHF", "USD", 20_000, t0);

        settlements.settle(authorized.authorization().id(), t0.plusSeconds(10), UUID.randomUUID());

        assertThatThrownBy(() -> settlements.settle(
                authorized.authorization().id(), t0.plusSeconds(20), UUID.randomUUID()))
                .isInstanceOf(SettlementException.WrongState.class);

        assertThat(walletBalance(customer, "USD"))
                .as("the customer must not be paid twice")
                .isEqualTo(22_400);
    }

    @Test
    @DisplayName("releasing an authorization gives the held funds back")
    void releaseReturnsHeldFunds() {
        Instant t0 = nextTimeline();
        publishRate("SGD", "USD", "0.74", t0);
        String customer = newCustomer("release");
        fund(customer, "SGD", 30_000);
        AuthorizationService.AuthorizationResult authorized = authorize(customer, "SGD", "USD", 30_000, t0);

        assertThat(walletBalance(customer, "SGD")).isZero();
        assertThat(heldBalance(customer, "SGD")).isEqualTo(30_000);

        FxAuthorization released = authorizations.release(
                authorized.authorization().id(), "customer cancelled", t0.plusSeconds(30));

        assertThat(released.status()).isEqualTo(AuthorizationStatus.RELEASED);
        assertThat(walletBalance(customer, "SGD")).isEqualTo(30_000);
        assertThat(heldBalance(customer, "SGD")).isZero();
        assertThat(walletBalance(customer, "USD")).isZero();
        assertBooksBalance();
    }

    @Test
    @DisplayName("a released authorization cannot then be settled")
    void releasedCannotSettle() {
        Instant t0 = nextTimeline();
        publishRate("SGD", "USD", "0.74", t0);
        String customer = newCustomer("released-settle");
        fund(customer, "SGD", 10_000);
        AuthorizationService.AuthorizationResult authorized = authorize(customer, "SGD", "USD", 10_000, t0);
        authorizations.release(authorized.authorization().id(), "cancelled", t0.plusSeconds(5));

        assertThatThrownBy(() -> settlements.settle(
                authorized.authorization().id(), t0.plusSeconds(10), UUID.randomUUID()))
                .isInstanceOf(SettlementException.WrongState.class);
    }

    @Test
    @DisplayName("an expired authorization cannot be settled")
    void expiredCannotSettle() {
        Instant t0 = nextTimeline();
        publishRate("EUR", "USD", "1.10", t0);
        String customer = newCustomer("expired");
        fund(customer, "EUR", 10_000);

        AuthorizationService.AuthorizationResult authorized = authorizations.authorize(
                new AuthorizationService.AuthorizeCommand(newReference("exp"), customer, "EUR", "USD",
                        10_000, t0.plusSeconds(60), Duration.ofMinutes(5)),
                UUID.randomUUID());

        assertThatThrownBy(() -> settlements.settle(
                authorized.authorization().id(), t0.plus(Duration.ofHours(2)), UUID.randomUUID()))
                .isInstanceOf(SettlementException.Expired.class);
    }

    @Test
    @DisplayName("the expiry sweeper releases held funds on abandoned quotes")
    void expirySweeperReleasesHolds() {
        Instant t0 = nextTimeline();
        publishRate("EUR", "USD", "1.10", t0);
        String customer = newCustomer("sweep");
        fund(customer, "EUR", 40_000);

        authorizations.authorize(new AuthorizationService.AuthorizeCommand(
                        newReference("sweep"), customer, "EUR", "USD", 40_000,
                        t0.plusSeconds(60), Duration.ofMinutes(10)),
                UUID.randomUUID());
        assertThat(walletBalance(customer, "EUR")).isZero();

        int expired = authorizations.expireDue(t0.plus(Duration.ofHours(1)), 100);

        assertThat(expired).isPositive();
        assertThat(walletBalance(customer, "EUR"))
                .as("abandoned quotes must not lock a customer's money up forever")
                .isEqualTo(40_000);
        assertBooksBalance();
    }

    @Test
    @DisplayName("an authorization that cannot be funded is rejected before anything is reserved")
    void underfundedAuthorizationIsRejected() {
        Instant t0 = nextTimeline();
        publishRate("EUR", "USD", "1.10", t0);
        String customer = newCustomer("underfunded");
        fund(customer, "EUR", 1_000);

        assertThatThrownBy(() -> authorize(customer, "EUR", "USD", 500_000, t0))
                .isInstanceOf(com.tribule.ledger.ledger.LedgerException.InsufficientFunds.class);

        assertThat(walletBalance(customer, "EUR")).isEqualTo(1_000);
        assertThat(heldBalance(customer, "EUR")).isZero();
        assertBooksBalance();
    }

    private AuthorizationService.AuthorizationResult authorize(
            String customer, String sell, String buy, long amount, Instant t0) {
        return authorizations.authorize(new AuthorizationService.AuthorizeCommand(
                        newReference("auth"), customer, sell, buy, amount,
                        t0.plusSeconds(60), Duration.ofDays(30)),
                UUID.randomUUID());
    }
}
