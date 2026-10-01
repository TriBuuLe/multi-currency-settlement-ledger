package com.tribule.ledger.support;

import com.tribule.ledger.fx.FxRateService;
import com.tribule.ledger.ledger.BalanceService;
import com.tribule.ledger.ledger.ChartOfAccounts;
import com.tribule.ledger.ledger.LedgerService;
import com.tribule.ledger.ledger.TrialBalanceLine;
import com.tribule.ledger.payments.TransferService;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for tests that need the real database.
 *
 * <p>Postgres, not H2. Half of what this ledger claims is enforced by Postgres
 * features an in-memory stand-in does not have -- deferrable constraint triggers,
 * {@code SET CONSTRAINTS ALL IMMEDIATE}, composite foreign keys, {@code ON CONFLICT}
 * upserts, {@code jsonb}. Testing against a substitute would verify the substitute.
 *
 * <p>One container for the whole JVM, started once and shared, because Spring caches
 * the context across test classes that configure it identically. Nothing is deleted
 * between tests: the journal is append-only by design and fighting that in test
 * setup would mean weakening it in production. Tests instead generate unique
 * customer ids and references, and assert on their own accounts and on invariants
 * that hold no matter what else is in the database.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractLedgerTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("ledger")
                    .withUsername("ledger")
                    .withPassword("ledger");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicLong TIMELINE = new AtomicLong();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected LedgerService ledger;
    @Autowired protected BalanceService balances;
    @Autowired protected TransferService transfers;
    @Autowired protected FxRateService fx;
    @Autowired protected JdbcClient jdbc;

    @BeforeAll
    static void containerIsUp() {
        assertThat(POSTGRES.isRunning()).isTrue();
    }

    /**
     * A fresh, far-future timeline for one test.
     *
     * <p>Tests share one database and the rate table is append-only, so every test
     * publishing EUR/USD into the same window would see every other test's prices.
     * Handing each test a window 400 days after the last one makes that impossible:
     * rate resolution takes the newest observation at or before the asked-for instant,
     * so an earlier test's rates are too old to win and a later test's are in the
     * future. Deterministic, and no cleanup required.
     */
    protected Instant nextTimeline() {
        return Instant.parse("2000-01-01T00:00:00Z")
                .plus(TIMELINE.incrementAndGet() * 400L, ChronoUnit.DAYS);
    }

    /** A customer id no other test will use. */
    protected String newCustomer(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected String newReference(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    protected void publishRate(String base, String quote, String rate, Instant effectiveAt) {
        fx.publish(base, quote, new BigDecimal(rate), effectiveAt, effectiveAt, "test");
    }

    protected void publishRate(String base, String quote, String rate) {
        publishRate(base, quote, rate, Instant.now().minusSeconds(60));
    }

    protected void fund(String customerId, String currency, long amountMinor) {
        transfers.fund(customerId, currency, amountMinor, newReference("fund"), Instant.now(), UUID.randomUUID());
    }

    protected long walletBalance(String customerId, String currency) {
        return balances.balanceOf(ChartOfAccounts.wallet(customerId, currency)).normalBalanceMinor();
    }

    protected long heldBalance(String customerId, String currency) {
        return balances.balanceOf(ChartOfAccounts.hold(customerId, currency)).normalBalanceMinor();
    }

    protected long houseBalance(String accountCode) {
        return balances.balanceOf(accountCode).normalBalanceMinor();
    }

    /**
     * The invariant that has to hold after anything this system does: every
     * currency's signed balances sum to zero.
     */
    protected void assertBooksBalance() {
        for (TrialBalanceLine line : balances.trialBalance()) {
            assertThat(line.residualMinor())
                    .as("trial balance for %s must be zero, debits=%d credits=%d",
                            line.currencyCode(), line.totalDebitsMinor(), line.totalCreditsMinor())
                    .isZero();
        }
    }
}
