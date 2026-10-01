package com.tribule.ledger.recon;

import com.tribule.ledger.ledger.Direction;
import com.tribule.ledger.ledger.SqlTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReconciliationRepository {

    /** What the ledger says about one external reference, on one nostro account. */
    public record LedgerMovement(String reference, UUID transactionId, Instant occurredAt,
                                 String currencyCode, long signedAmountMinor, int transactionCount) {
    }

    private final JdbcClient jdbc;

    public ReconciliationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public StatementBatch insertBatch(String source, String filename, LocalDate asOfDate, int lineCount) {
        try {
            return jdbc.sql("""
                    INSERT INTO statement_batch (source, filename, as_of_date, line_count)
                    VALUES (?, ?, ?, ?)
                    RETURNING id, source, filename, as_of_date, line_count, imported_at
                    """)
                    .params(source, filename, asOfDate, lineCount)
                    .query((rs, n) -> new StatementBatch(
                            rs.getObject("id", UUID.class),
                            rs.getString("source"),
                            rs.getString("filename"),
                            rs.getObject("as_of_date", LocalDate.class),
                            rs.getInt("line_count"),
                            SqlTime.fromDb(rs, "imported_at")))
                    .single();
        } catch (DuplicateKeyException e) {
            throw new ReconciliationException.DuplicateBatch(source, filename);
        }
    }

    public void insertLines(UUID batchId, List<StatementCsvParser.ParsedLine> lines) {
        for (StatementCsvParser.ParsedLine line : lines) {
            jdbc.sql("""
                    INSERT INTO statement_line
                        (batch_id, line_number, external_ref, posted_at, currency_code,
                         amount_minor, direction, description)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(batchId, line.lineNumber(), line.externalRef(), SqlTime.toDb(line.postedAt()),
                            line.currencyCode(), line.amountMinor(), line.direction().name(), line.description())
                    .update();
        }
    }

    public Optional<StatementBatch> findBatch(UUID id) {
        return jdbc.sql("""
                SELECT id, source, filename, as_of_date, line_count, imported_at
                  FROM statement_batch WHERE id = ?
                """)
                .param(id)
                .query((rs, n) -> new StatementBatch(
                        rs.getObject("id", UUID.class),
                        rs.getString("source"),
                        rs.getString("filename"),
                        rs.getObject("as_of_date", LocalDate.class),
                        rs.getInt("line_count"),
                        SqlTime.fromDb(rs, "imported_at")))
                .optional();
    }

    public List<StatementLine> findLines(UUID batchId) {
        return jdbc.sql("""
                SELECT id, batch_id, line_number, external_ref, posted_at, currency_code,
                       amount_minor, direction, description, match_status, matched_transaction_id
                  FROM statement_line WHERE batch_id = ? ORDER BY line_number
                """)
                .param(batchId)
                .query((rs, n) -> new StatementLine(
                        rs.getObject("id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getInt("line_number"),
                        rs.getString("external_ref"),
                        SqlTime.fromDb(rs, "posted_at"),
                        rs.getString("currency_code").trim(),
                        rs.getLong("amount_minor"),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getString("description"),
                        rs.getString("match_status"),
                        rs.getObject("matched_transaction_id", UUID.class)))
                .list();
    }

    public void markLine(UUID lineId, String matchStatus, UUID matchedTransactionId) {
        jdbc.sql("UPDATE statement_line SET match_status = ?, matched_transaction_id = ? WHERE id = ?")
                .params(matchStatus, matchedTransactionId, lineId)
                .update();
    }

    /**
     * What the ledger did to a nostro account under one external reference.
     *
     * <p>Summed across every transaction carrying that reference, so a payment
     * that was later reversed nets to zero and matches a statement that never
     * showed it -- rather than appearing as two separate unexplained breaks.
     */
    public Optional<LedgerMovement> findLedgerMovement(String reference, String nostroAccountCode) {
        return jdbc.sql("""
                SELECT t.reference,
                       (array_agg(t.id ORDER BY t.occurred_at))[1] AS transaction_id,
                       MIN(t.occurred_at)    AS occurred_at,
                       e.currency_code,
                       SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END) AS signed_minor,
                       count(DISTINCT t.id)  AS transaction_count
                  FROM journal_transaction t
                  JOIN journal_entry e ON e.transaction_id = t.id
                  JOIN account a       ON a.id = e.account_id
                 WHERE t.reference = ? AND a.code = ?
                 GROUP BY t.reference, e.currency_code
                """)
                .params(reference, nostroAccountCode)
                .query((rs, n) -> new LedgerMovement(
                        rs.getString("reference"),
                        rs.getObject("transaction_id", UUID.class),
                        SqlTime.fromDb(rs, "occurred_at"),
                        rs.getString("currency_code").trim(),
                        rs.getLong("signed_minor"),
                        rs.getInt("transaction_count")))
                .optional();
    }

    /** Every referenced nostro movement in a window: the other half of the comparison. */
    public List<LedgerMovement> findLedgerMovements(String nostroAccountCode, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT t.reference,
                       (array_agg(t.id ORDER BY t.occurred_at))[1] AS transaction_id,
                       MIN(t.occurred_at)    AS occurred_at,
                       e.currency_code,
                       SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END) AS signed_minor,
                       count(DISTINCT t.id)  AS transaction_count
                  FROM journal_transaction t
                  JOIN journal_entry e ON e.transaction_id = t.id
                  JOIN account a       ON a.id = e.account_id
                 WHERE a.code = ? AND t.reference IS NOT NULL
                   AND t.occurred_at >= ? AND t.occurred_at < ?
                 GROUP BY t.reference, e.currency_code
                HAVING SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END) <> 0
                 ORDER BY MIN(t.occurred_at)
                """)
                .params(nostroAccountCode, SqlTime.toDb(from), SqlTime.toDb(to))
                .query((rs, n) -> new LedgerMovement(
                        rs.getString("reference"),
                        rs.getObject("transaction_id", UUID.class),
                        SqlTime.fromDb(rs, "occurred_at"),
                        rs.getString("currency_code").trim(),
                        rs.getLong("signed_minor"),
                        rs.getInt("transaction_count")))
                .list();
    }

    public ReconciliationBreak insertBreak(ReconciliationBreak reconciliationBreak) {
        return jdbc.sql("""
                INSERT INTO reconciliation_break
                    (batch_id, statement_line_id, transaction_id, break_type, currency_code,
                     delta_minor, status, resolution, adjustment_transaction_id, resolved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, batch_id, statement_line_id, transaction_id, break_type, currency_code,
                          delta_minor, status, resolution, adjustment_transaction_id, detected_at, resolved_at
                """)
                .params(reconciliationBreak.batchId(),
                        reconciliationBreak.statementLineId(),
                        reconciliationBreak.transactionId(),
                        reconciliationBreak.breakType().name(),
                        reconciliationBreak.currencyCode(),
                        reconciliationBreak.deltaMinor(),
                        reconciliationBreak.status().name(),
                        reconciliationBreak.resolution(),
                        reconciliationBreak.adjustmentTransactionId(),
                        SqlTime.toDb(reconciliationBreak.resolvedAt()))
                .query(this::mapBreak)
                .single();
    }

    public List<ReconciliationBreak> findBreaks(UUID batchId) {
        return jdbc.sql("""
                SELECT id, batch_id, statement_line_id, transaction_id, break_type, currency_code,
                       delta_minor, status, resolution, adjustment_transaction_id, detected_at, resolved_at
                  FROM reconciliation_break WHERE batch_id = ? ORDER BY detected_at, break_type
                """)
                .param(batchId)
                .query(this::mapBreak)
                .list();
    }

    public int countOpenBreaks() {
        return jdbc.sql("SELECT count(*) FROM reconciliation_break WHERE status = 'OPEN'")
                .query(Integer.class).single();
    }

    private ReconciliationBreak mapBreak(ResultSet rs, int rowNum) throws SQLException {
        return new ReconciliationBreak(
                rs.getObject("id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getObject("statement_line_id", UUID.class),
                rs.getObject("transaction_id", UUID.class),
                BreakType.valueOf(rs.getString("break_type")),
                rs.getString("currency_code") == null ? null : rs.getString("currency_code").trim(),
                rs.getLong("delta_minor"),
                BreakStatus.valueOf(rs.getString("status")),
                rs.getString("resolution"),
                rs.getObject("adjustment_transaction_id", UUID.class),
                SqlTime.fromDb(rs, "detected_at"),
                SqlTime.fromDb(rs, "resolved_at"));
    }
}
