package com.tribule.ledger.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Reads the balance projection, its snapshots, and the trial balance. */
@Repository
public class BalanceRepository {

    /** A balance recomputed from the journal, with the snapshot it started from. */
    public record RebuiltBalance(UUID accountId, long balanceMinor, long entryCount,
                                 Long lastEntryId, long snapshotEntryId, long entriesReplayed) {
    }

    private static final String BALANCE_SELECT = """
            SELECT b.account_id, a.code AS account_code, b.currency_code, b.balance_minor,
                   a.normal_side, b.entry_count, b.last_entry_id, b.version, b.updated_at
              FROM account_balance b JOIN account a ON a.id = b.account_id
            """;

    private final JdbcClient jdbc;

    public BalanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AccountBalance> findByCode(String accountCode) {
        return jdbc.sql(BALANCE_SELECT + " WHERE a.code = ?").param(accountCode).query(this::map).optional();
    }

    public Optional<AccountBalance> findByAccountId(UUID accountId) {
        return jdbc.sql(BALANCE_SELECT + " WHERE b.account_id = ?").param(accountId).query(this::map).optional();
    }

    public List<AccountBalance> findByCurrency(String currencyCode) {
        return jdbc.sql(BALANCE_SELECT + " WHERE b.currency_code = ? ORDER BY a.code")
                .param(currencyCode).query(this::map).list();
    }

    public List<AccountBalance> findAll() {
        return jdbc.sql(BALANCE_SELECT + " ORDER BY b.currency_code, a.code").query(this::map).list();
    }

    /**
     * The trial balance, straight from the projection.
     *
     * <p>{@code residual_minor} is the number this whole system exists to keep at
     * zero: the signed sum of every account in a currency.
     */
    public List<TrialBalanceLine> trialBalance() {
        return jdbc.sql("""
                SELECT currency_code,
                       COALESCE(SUM(CASE WHEN balance_minor > 0 THEN  balance_minor ELSE 0 END), 0) AS debits,
                       COALESCE(SUM(CASE WHEN balance_minor < 0 THEN -balance_minor ELSE 0 END), 0) AS credits,
                       COALESCE(SUM(balance_minor), 0)                                             AS residual,
                       count(*)                                                                    AS accounts
                  FROM account_balance
                 GROUP BY currency_code
                 ORDER BY currency_code
                """)
                .query((rs, n) -> new TrialBalanceLine(
                        rs.getString("currency_code").trim(),
                        rs.getLong("debits"),
                        rs.getLong("credits"),
                        rs.getLong("residual"),
                        rs.getLong("accounts")))
                .list();
    }

    /**
     * The same trial balance, computed from the journal instead of the
     * projection. Comparing the two is the verifier's whole job.
     */
    public List<TrialBalanceLine> trialBalanceFromJournal() {
        return jdbc.sql("""
                SELECT currency_code,
                       COALESCE(SUM(CASE WHEN direction = 'DEBIT'  THEN amount_minor ELSE 0 END), 0) AS debits,
                       COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE 0 END), 0) AS credits,
                       COALESCE(SUM(CASE WHEN direction = 'DEBIT'  THEN amount_minor ELSE -amount_minor END), 0) AS residual,
                       count(DISTINCT account_id) AS accounts
                  FROM journal_entry
                 GROUP BY currency_code
                 ORDER BY currency_code
                """)
                .query((rs, n) -> new TrialBalanceLine(
                        rs.getString("currency_code").trim(),
                        rs.getLong("debits"),
                        rs.getLong("credits"),
                        rs.getLong("residual"),
                        rs.getLong("accounts")))
                .list();
    }

    /**
     * Recomputes one account's balance from the journal, starting at its newest
     * snapshot. Without the snapshot this is a full scan of the account's
     * history, which is fine on day one and useless a year later.
     */
    public RebuiltBalance rebuild(UUID accountId) {
        return jdbc.sql("""
                WITH snap AS (
                    SELECT up_to_entry_id, balance_minor, entry_count
                      FROM balance_snapshot
                     WHERE account_id = ?
                     ORDER BY up_to_entry_id DESC
                     LIMIT 1
                ),
                base AS (
                    SELECT COALESCE((SELECT up_to_entry_id FROM snap), 0) AS from_entry_id,
                           COALESCE((SELECT balance_minor  FROM snap), 0) AS from_balance,
                           COALESCE((SELECT entry_count    FROM snap), 0) AS from_count
                ),
                delta AS (
                    SELECT COALESCE(SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END), 0) AS sum_minor,
                           count(*)          AS n,
                           MAX(e.id)         AS max_id
                      FROM journal_entry e, base
                     WHERE e.account_id = ? AND e.id > base.from_entry_id
                )
                SELECT base.from_balance + delta.sum_minor AS balance_minor,
                       base.from_count   + delta.n        AS entry_count,
                       GREATEST(COALESCE(delta.max_id, 0), base.from_entry_id) AS last_entry_id,
                       base.from_entry_id AS snapshot_entry_id,
                       delta.n            AS entries_replayed
                  FROM base, delta
                """)
                .params(accountId, accountId)
                .query((rs, n) -> new RebuiltBalance(
                        accountId,
                        rs.getLong("balance_minor"),
                        rs.getLong("entry_count"),
                        rs.getLong("last_entry_id") == 0 ? null : rs.getLong("last_entry_id"),
                        rs.getLong("snapshot_entry_id"),
                        rs.getLong("entries_replayed")))
                .single();
    }

    /** Checkpoints an account at its current projected balance. */
    public int snapshot(UUID accountId) {
        return jdbc.sql("""
                INSERT INTO balance_snapshot (account_id, up_to_entry_id, balance_minor, entry_count)
                SELECT account_id, last_entry_id, balance_minor, entry_count
                  FROM account_balance
                 WHERE account_id = ? AND last_entry_id IS NOT NULL
                ON CONFLICT (account_id, up_to_entry_id) DO NOTHING
                """)
                .param(accountId)
                .update();
    }

    public int snapshotAll() {
        return jdbc.sql("""
                INSERT INTO balance_snapshot (account_id, up_to_entry_id, balance_minor, entry_count)
                SELECT account_id, last_entry_id, balance_minor, entry_count
                  FROM account_balance
                 WHERE last_entry_id IS NOT NULL
                ON CONFLICT (account_id, up_to_entry_id) DO NOTHING
                """)
                .update();
    }

    /** Transactions whose legs do not sum to zero in some currency. Should always be empty. */
    public List<UUID> findUnbalancedTransactions(int limit) {
        return jdbc.sql("""
                SELECT transaction_id
                  FROM journal_entry
                 GROUP BY transaction_id, currency_code
                HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0
                 LIMIT ?
                """)
                .param(limit)
                .query((rs, n) -> rs.getObject("transaction_id", UUID.class))
                .list();
    }

    /** Accounts where the projection disagrees with a fresh replay of the journal. */
    public List<UUID> findDriftedAccounts(int limit) {
        return jdbc.sql("""
                SELECT b.account_id
                  FROM account_balance b
                  LEFT JOIN (
                      SELECT account_id,
                             SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) AS balance_minor,
                             count(*) AS entry_count
                        FROM journal_entry
                       GROUP BY account_id
                  ) j ON j.account_id = b.account_id
                 WHERE b.balance_minor <> COALESCE(j.balance_minor, 0)
                    OR b.entry_count   <> COALESCE(j.entry_count, 0)
                 LIMIT ?
                """)
                .param(limit)
                .query((rs, n) -> rs.getObject("account_id", UUID.class))
                .list();
    }

    /**
     * Totals the normal-side balances of every account whose code starts with a
     * prefix, by currency.
     *
     * <p>Used for cross-domain checks such as "do the customer hold accounts add
     * up to the open authorizations?" -- a question that spans two subsystems and
     * is therefore exactly the kind that silently goes wrong.
     */
    public Map<String, Long> sumNormalBalanceByCodePrefix(String codePrefix) {
        Map<String, Long> totals = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT b.currency_code,
                       SUM(CASE WHEN a.normal_side = 'DEBIT' THEN b.balance_minor ELSE -b.balance_minor END) AS total
                  FROM account_balance b JOIN account a ON a.id = b.account_id
                 WHERE a.code LIKE ?
                 GROUP BY b.currency_code
                """)
                .param(codePrefix + "%")
                .query((rs, n) -> Map.entry(rs.getString("currency_code").trim(), rs.getLong("total")))
                .list()
                .forEach(e -> totals.put(e.getKey(), e.getValue()));
        return totals;
    }

    /** Accounts that are forbidden from going negative and have gone negative anyway. */
    public List<String> findOverdrawnAccounts(int limit) {
        return jdbc.sql("""
                SELECT a.code
                  FROM account_balance b JOIN account a ON a.id = b.account_id
                 WHERE NOT a.allow_negative_balance
                   AND (CASE WHEN a.normal_side = 'DEBIT' THEN b.balance_minor ELSE -b.balance_minor END) < 0
                 ORDER BY a.code
                 LIMIT ?
                """)
                .param(limit)
                .query(String.class)
                .list();
    }

    private AccountBalance map(ResultSet rs, int rowNum) throws SQLException {
        long signed = rs.getLong("balance_minor");
        Direction normalSide = Direction.valueOf(rs.getString("normal_side"));
        Long lastEntryId = rs.getObject("last_entry_id") == null ? null : rs.getLong("last_entry_id");
        return new AccountBalance(
                rs.getObject("account_id", UUID.class),
                rs.getString("account_code"),
                rs.getString("currency_code").trim(),
                signed,
                normalSide == Direction.DEBIT ? signed : -signed,
                rs.getLong("entry_count"),
                lastEntryId,
                rs.getLong("version"),
                SqlTime.fromDb(rs, "updated_at"));
    }
}
