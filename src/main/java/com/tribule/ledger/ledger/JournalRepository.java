package com.tribule.ledger.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Reads and appends to the journal. Nothing here updates or deletes; the database would refuse. */
@Repository
public class JournalRepository {

    /** One leg with its account already resolved, ready to be written. */
    public record ResolvedPosting(Account account, Direction direction, long amountMinor, String memo) {
    }

    private final JdbcClient jdbc;

    public JournalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertTransaction(PostingCommand command) {
        jdbc.sql("""
                INSERT INTO journal_transaction
                    (id, kind, reference, description, occurred_at,
                     reverses_transaction_id, fx_rate_id, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(command.id(),
                        command.kind().name(),
                        command.reference(),
                        command.description(),
                        SqlTime.toDb(command.occurredAt()),
                        command.reversesTransactionId(),
                        command.fxRateId(),
                        command.correlationId())
                .update();
    }

    /**
     * Writes every leg in a single statement.
     *
     * <p>One multi-row INSERT with RETURNING instead of a loop: a transfer is
     * two legs and a settlement is six, and a per-leg round trip would make
     * network latency the dominant cost of posting.
     */
    public List<JournalEntry> insertEntries(UUID transactionId, List<ResolvedPosting> postings) {
        StringBuilder sql = new StringBuilder("""
                INSERT INTO journal_entry
                    (transaction_id, account_id, currency_code, direction, amount_minor, entry_seq, memo)
                VALUES
                """);
        List<Object> params = new ArrayList<>(postings.size() * 7);
        for (int i = 0; i < postings.size(); i++) {
            ResolvedPosting posting = postings.get(i);
            sql.append(i == 0 ? "" : ", ").append("(?, ?, ?, ?, ?, ?, ?)");
            params.add(transactionId);
            params.add(posting.account().id());
            params.add(posting.account().currency().code());
            params.add(posting.direction().name());
            params.add(posting.amountMinor());
            params.add((short) (i + 1));
            params.add(posting.memo());
        }
        sql.append(" RETURNING id, transaction_id, account_id, currency_code, direction, amount_minor, entry_seq, memo");

        List<JournalEntry> written = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> mapEntry(rs, null))
                .list();

        // RETURNING order is not contractually insertion order, so reattach
        // account codes by sequence rather than by position.
        List<String> codesBySeq = postings.stream().map(p -> p.account().code()).collect(Collectors.toList());
        return written.stream()
                .sorted(Comparator.comparingInt(JournalEntry::entrySeq))
                .map(e -> new JournalEntry(e.id(), e.transactionId(), e.accountId(),
                        codesBySeq.get(e.entrySeq() - 1), e.currencyCode(), e.direction(),
                        e.amountMinor(), e.entrySeq(), e.memo()))
                .toList();
    }

    /**
     * Forces every deferred constraint to be checked now, while we are still
     * inside the transaction and still have the context to explain the failure.
     *
     * <p>Without this, the balanced-transaction and overdraft triggers fire at
     * COMMIT -- after the service method has returned -- and the caller gets an
     * opaque transaction-rollback error instead of "insufficient funds in
     * LIABILITY:CUSTOMER:alice:USD".
     */
    public void flushDeferredConstraints() {
        jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
    }

    public Optional<JournalTransaction> findTransaction(UUID id) {
        return jdbc.sql("""
                SELECT id, kind, reference, description, occurred_at, recorded_at,
                       reverses_transaction_id, fx_rate_id, correlation_id
                  FROM journal_transaction WHERE id = ?
                """)
                .param(id)
                .query(this::mapTransaction)
                .optional();
    }

    public List<JournalEntry> findEntriesByTransaction(UUID transactionId) {
        return jdbc.sql("""
                SELECT e.id, e.transaction_id, e.account_id, a.code AS account_code, e.currency_code,
                       e.direction, e.amount_minor, e.entry_seq, e.memo
                  FROM journal_entry e JOIN account a ON a.id = e.account_id
                 WHERE e.transaction_id = ?
                 ORDER BY e.entry_seq
                """)
                .param(transactionId)
                .query((rs, n) -> mapEntry(rs, rs.getString("account_code")))
                .list();
    }

    public List<JournalEntry> findEntriesByAccount(UUID accountId, long afterEntryId, int limit) {
        return jdbc.sql("""
                SELECT e.id, e.transaction_id, e.account_id, a.code AS account_code, e.currency_code,
                       e.direction, e.amount_minor, e.entry_seq, e.memo
                  FROM journal_entry e JOIN account a ON a.id = e.account_id
                 WHERE e.account_id = ? AND e.id > ?
                 ORDER BY e.id
                 LIMIT ?
                """)
                .params(accountId, afterEntryId, limit)
                .query((rs, n) -> mapEntry(rs, rs.getString("account_code")))
                .list();
    }

    /** True once a reversal referencing this transaction exists. */
    public boolean isReversed(UUID transactionId) {
        return Boolean.TRUE.equals(jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM journal_transaction WHERE reverses_transaction_id = ?)")
                .param(transactionId)
                .query(Boolean.class)
                .single());
    }

    public long countTransactions() {
        return jdbc.sql("SELECT count(*) FROM journal_transaction").query(Long.class).single();
    }

    public long countEntries() {
        return jdbc.sql("SELECT count(*) FROM journal_entry").query(Long.class).single();
    }

    /** Transactions that used a given rate -- the entry point for correction replay. */
    public List<JournalTransaction> findByFxRateIds(List<UUID> rateIds) {
        if (rateIds.isEmpty()) {
            return List.of();
        }
        String placeholders = rateIds.stream().map(x -> "?").collect(Collectors.joining(", "));
        return jdbc.sql("""
                SELECT id, kind, reference, description, occurred_at, recorded_at,
                       reverses_transaction_id, fx_rate_id, correlation_id
                  FROM journal_transaction
                 WHERE fx_rate_id IN (%s)
                 ORDER BY occurred_at
                """.formatted(placeholders))
                .params(rateIds.stream().map(Object.class::cast).toList())
                .query(this::mapTransaction)
                .list();
    }

    private JournalTransaction mapTransaction(ResultSet rs, int rowNum) throws SQLException {
        return new JournalTransaction(
                rs.getObject("id", UUID.class),
                TransactionKind.valueOf(rs.getString("kind")),
                rs.getString("reference"),
                rs.getString("description"),
                SqlTime.fromDb(rs, "occurred_at"),
                SqlTime.fromDb(rs, "recorded_at"),
                rs.getObject("reverses_transaction_id", UUID.class),
                rs.getObject("fx_rate_id", UUID.class),
                rs.getString("correlation_id"));
    }

    private JournalEntry mapEntry(ResultSet rs, String accountCode) throws SQLException {
        return new JournalEntry(
                rs.getLong("id"),
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                accountCode,
                rs.getString("currency_code").trim(),
                Direction.valueOf(rs.getString("direction")),
                rs.getLong("amount_minor"),
                rs.getShort("entry_seq"),
                rs.getString("memo"));
    }
}
