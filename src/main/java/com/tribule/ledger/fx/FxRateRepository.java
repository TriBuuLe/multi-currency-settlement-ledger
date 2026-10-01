package com.tribule.ledger.fx;

import com.tribule.ledger.ledger.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FxRateRepository {

    private static final String COLUMNS = """
            id, base_currency, quote_currency, rate, effective_at, observed_at, source, supersedes_id
            """;

    private final JdbcClient jdbc;

    public FxRateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public FxRate insert(String base, String quote, BigDecimal rate,
                         Instant effectiveAt, Instant observedAt, String source, UUID supersedesId) {
        return jdbc.sql("""
                INSERT INTO fx_rate (base_currency, quote_currency, rate, effective_at, observed_at, source, supersedes_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING
                """ + COLUMNS)
                .params(base, quote, rate,
                        SqlTime.toDb(effectiveAt), SqlTime.toDb(observedAt), source, supersedesId)
                .query(this::map)
                .single();
    }

    /**
     * The bitemporal lookup: the newest price that was already in effect at
     * {@code effectiveAt}, using only rows we had already observed by
     * {@code knownAt}.
     *
     * <p>Dropping the second predicate would turn every historical report into a
     * different answer each time it ran, because later corrections would leak
     * backwards into it.
     */
    public Optional<FxRate> findAsOf(String base, String quote, Instant effectiveAt, Instant knownAt) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM fx_rate
                 WHERE base_currency = ? AND quote_currency = ?
                   AND effective_at <= ? AND observed_at <= ?
                 ORDER BY effective_at DESC, observed_at DESC
                 LIMIT 1
                """)
                .params(base, quote, SqlTime.toDb(effectiveAt), SqlTime.toDb(knownAt))
                .query(this::map)
                .optional();
    }

    public Optional<FxRate> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fx_rate WHERE id = ?").param(id).query(this::map).optional();
    }

    /** Every observation of a pair in a window, oldest first. Used for the VaR history. */
    public List<FxRate> findHistory(String base, String quote, Instant from, Instant to, Instant knownAt) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM fx_rate r
                 WHERE r.base_currency = ? AND r.quote_currency = ?
                   AND r.effective_at >= ? AND r.effective_at <= ? AND r.observed_at <= ?
                   -- keep only the latest observation of each effective instant,
                   -- so a corrected tick does not appear twice in the series
                   AND NOT EXISTS (
                       SELECT 1 FROM fx_rate newer
                        WHERE newer.base_currency = r.base_currency
                          AND newer.quote_currency = r.quote_currency
                          AND newer.effective_at = r.effective_at
                          AND newer.observed_at > r.observed_at
                          AND newer.observed_at <= ?
                   )
                 ORDER BY r.effective_at
                """)
                .params(base, quote, SqlTime.toDb(from), SqlTime.toDb(to),
                        SqlTime.toDb(knownAt), SqlTime.toDb(knownAt))
                .query(this::map)
                .list();
    }

    /** Corrections published against a given rate. */
    public List<FxRate> findCorrectionsOf(UUID rateId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fx_rate WHERE supersedes_id = ? ORDER BY observed_at")
                .param(rateId)
                .query(this::map)
                .list();
    }

    public List<String> distinctPairs() {
        return jdbc.sql("SELECT DISTINCT base_currency || '/' || quote_currency AS pair FROM fx_rate ORDER BY pair")
                .query(String.class)
                .list();
    }

    private FxRate map(ResultSet rs, int rowNum) throws SQLException {
        return new FxRate(
                rs.getObject("id", UUID.class),
                rs.getString("base_currency").trim(),
                rs.getString("quote_currency").trim(),
                rs.getBigDecimal("rate"),
                SqlTime.fromDb(rs, "effective_at"),
                SqlTime.fromDb(rs, "observed_at"),
                rs.getString("source"),
                rs.getObject("supersedes_id", UUID.class));
    }
}
