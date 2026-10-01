package com.tribule.ledger.settlement;

import com.tribule.ledger.ledger.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AuthorizationRepository {

    private static final String COLUMNS = """
            id, reference, customer_id, sell_currency, buy_currency, sell_amount_minor,
            quoted_buy_amount_minor, quoted_rate, quoted_rate_id, status, authorized_at, expires_at,
            settled_at, hold_transaction_id, settlement_transaction_id, settlement_rate,
            settlement_rate_id, realized_pnl_minor, realized_pnl_currency, version
            """;

    private final JdbcClient jdbc;

    public AuthorizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public FxAuthorization insert(FxAuthorization authorization) {
        return jdbc.sql("""
                INSERT INTO fx_authorization
                    (id, reference, customer_id, sell_currency, buy_currency, sell_amount_minor,
                     quoted_buy_amount_minor, quoted_rate, quoted_rate_id, status,
                     authorized_at, expires_at, hold_transaction_id, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                RETURNING
                """ + COLUMNS)
                .params(authorization.id(),
                        authorization.reference(),
                        authorization.customerId(),
                        authorization.sellCurrency(),
                        authorization.buyCurrency(),
                        authorization.sellAmountMinor(),
                        authorization.quotedBuyAmountMinor(),
                        authorization.quotedRate(),
                        authorization.quotedRateId(),
                        authorization.status().name(),
                        SqlTime.toDb(authorization.authorizedAt()),
                        SqlTime.toDb(authorization.expiresAt()),
                        authorization.holdTransactionId())
                .query(this::map)
                .single();
    }

    public Optional<FxAuthorization> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fx_authorization WHERE id = ?")
                .param(id).query(this::map).optional();
    }

    public Optional<FxAuthorization> findByReference(String reference) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fx_authorization WHERE reference = ?")
                .param(reference).query(this::map).optional();
    }

    public List<FxAuthorization> findPending() {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM fx_authorization WHERE status = 'PENDING'
                 ORDER BY sell_currency, buy_currency, authorized_at
                """).query(this::map).list();
    }

    public List<FxAuthorization> findPendingExpiredAt(Instant now, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM fx_authorization
                 WHERE status = 'PENDING' AND expires_at <= ?
                 ORDER BY expires_at
                 LIMIT ?
                """).params(SqlTime.toDb(now), limit).query(this::map).list();
    }

    public List<FxAuthorization> findByCustomer(String customerId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM fx_authorization WHERE customer_id = ?
                 ORDER BY authorized_at DESC LIMIT ?
                """).params(customerId, limit).query(this::map).list();
    }

    /**
     * Moves an authorization to SETTLED, but only from the exact version the
     * caller read.
     *
     * <p>This is the guard against paying a customer twice: two concurrent
     * settlement requests both post their ledger transaction, but only one can
     * match {@code version}, and the loser's whole transaction -- ledger entries
     * included -- rolls back with it.
     */
    public boolean markSettled(UUID id, int expectedVersion, UUID settlementTransactionId,
                               BigDecimal settlementRate, UUID settlementRateId,
                               long realizedPnlMinor, String realizedPnlCurrency, Instant settledAt) {
        int updated = jdbc.sql("""
                UPDATE fx_authorization
                   SET status = 'SETTLED',
                       settlement_transaction_id = ?,
                       settlement_rate = ?,
                       settlement_rate_id = ?,
                       realized_pnl_minor = ?,
                       realized_pnl_currency = ?,
                       settled_at = ?,
                       version = version + 1
                 WHERE id = ? AND version = ? AND status = 'PENDING'
                """)
                .params(settlementTransactionId, settlementRate, settlementRateId,
                        realizedPnlMinor, realizedPnlCurrency, SqlTime.toDb(settledAt), id, expectedVersion)
                .update();
        return updated == 1;
    }

    public boolean markClosed(UUID id, int expectedVersion, AuthorizationStatus status, Instant closedAt) {
        int updated = jdbc.sql("""
                UPDATE fx_authorization
                   SET status = ?, settled_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status = 'PENDING'
                """)
                .params(status.name(), SqlTime.toDb(closedAt), id, expectedVersion)
                .update();
        return updated == 1;
    }

    /** Settled authorizations priced off one of the given rate rows. Drives correction replay. */
    public List<FxAuthorization> findSettledPricedOn(List<UUID> rateIds) {
        if (rateIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", rateIds.stream().map(x -> "?").toList());
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM fx_authorization
                 WHERE status = 'SETTLED' AND settlement_rate_id IN (%s)
                 ORDER BY settled_at
                """.formatted(placeholders))
                .params(rateIds.stream().map(Object.class::cast).toList())
                .query(this::map)
                .list();
    }

    /** Quoted buy-side notional of open authorizations, by currency. */
    public Map<String, Long> pendingQuotedByBuyCurrency() {
        return sumPending("buy_currency", "quoted_buy_amount_minor");
    }

    /** Sell-side notional of open authorizations, by currency: what should be sitting in holds. */
    public Map<String, Long> pendingSellByCurrency() {
        return sumPending("sell_currency", "sell_amount_minor");
    }

    private Map<String, Long> sumPending(String currencyColumn, String amountColumn) {
        Map<String, Long> totals = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT %s AS currency, SUM(%s) AS total
                  FROM fx_authorization
                 WHERE status = 'PENDING'
                 GROUP BY %s
                """.formatted(currencyColumn, amountColumn, currencyColumn))
                .query((rs, n) -> Map.entry(rs.getString("currency").trim(), rs.getLong("total")))
                .list()
                .forEach(e -> totals.put(e.getKey(), e.getValue()));
        return totals;
    }

    private FxAuthorization map(ResultSet rs, int rowNum) throws SQLException {
        return new FxAuthorization(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getString("customer_id"),
                rs.getString("sell_currency").trim(),
                rs.getString("buy_currency").trim(),
                rs.getLong("sell_amount_minor"),
                rs.getLong("quoted_buy_amount_minor"),
                rs.getBigDecimal("quoted_rate"),
                rs.getObject("quoted_rate_id", UUID.class),
                AuthorizationStatus.valueOf(rs.getString("status")),
                SqlTime.fromDb(rs, "authorized_at"),
                SqlTime.fromDb(rs, "expires_at"),
                SqlTime.fromDb(rs, "settled_at"),
                rs.getObject("hold_transaction_id", UUID.class),
                rs.getObject("settlement_transaction_id", UUID.class),
                rs.getBigDecimal("settlement_rate"),
                rs.getObject("settlement_rate_id", UUID.class),
                rs.getObject("realized_pnl_minor") == null ? null : rs.getLong("realized_pnl_minor"),
                rs.getString("realized_pnl_currency") == null ? null : rs.getString("realized_pnl_currency").trim(),
                rs.getInt("version"));
    }
}
