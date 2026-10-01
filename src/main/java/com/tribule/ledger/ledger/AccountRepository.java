package com.tribule.ledger.ledger;

import com.tribule.ledger.money.CurrencyUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The chart of accounts.
 *
 * <p>Accounts are immutable once created, so they are cached by code. Every
 * posting resolves at least two of them, and re-reading reference data on the
 * hot path is the easiest throughput win in the whole service.
 */
@Repository
public class AccountRepository {

    private static final String COLUMNS = """
            id, code, name, currency_code, account_type, normal_side, is_contingent, allow_negative_balance
            """;

    private final JdbcClient jdbc;
    private final CurrencyRepository currencies;
    private final Map<String, Account> byCode = new ConcurrentHashMap<>();

    public AccountRepository(JdbcClient jdbc, CurrencyRepository currencies) {
        this.jdbc = jdbc;
        this.currencies = currencies;
    }

    public Optional<Account> findByCode(String code) {
        Account cached = byCode.get(code);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Account> found = jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE code = ?")
                .param(code)
                .query(this::map)
                .optional();
        found.ifPresent(a -> byCode.put(a.code(), a));
        return found;
    }

    public Account require(String code) {
        return findByCode(code).orElseThrow(() -> new LedgerException.AccountNotFound(code));
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE id = ?")
                .param(id)
                .query(this::map)
                .optional();
    }

    public Map<String, Account> requireAll(Collection<String> codes) {
        Map<String, Account> result = new LinkedHashMap<>();
        for (String code : codes) {
            result.put(code, require(code));
        }
        return result;
    }

    public List<Account> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account ORDER BY code").query(this::map).list();
    }

    public List<Account> findByCurrency(String currencyCode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE currency_code = ? ORDER BY code")
                .param(currencyCode)
                .query(this::map)
                .list();
    }

    /**
     * Returns the customer's wallet for a currency, creating it if this is the
     * first time we have seen the pair.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than check-then-insert: two
     * concurrent first-time requests for the same wallet would otherwise race
     * and one would fail on the unique index.
     */
    public Account ensureCustomerWallet(String customerId, String currencyCode) {
        CurrencyUnit currency = currencies.require(currencyCode);
        String code = ChartOfAccounts.wallet(customerId, currency.code());
        Account cached = byCode.get(code);
        if (cached != null) {
            return cached;
        }
        jdbc.sql("""
                INSERT INTO account
                    (code, name, currency_code, account_type, normal_side, is_contingent, allow_negative_balance)
                VALUES (?, ?, ?, 'LIABILITY', 'CREDIT', FALSE, FALSE)
                ON CONFLICT (code) DO NOTHING
                """)
                .params(code, "Customer wallet " + customerId + " " + currency.code(), currency.code())
                .update();
        return require(code);
    }

    /**
     * Returns the customer's hold account for a currency, creating it on first
     * use. Holds are allowed to be created lazily for the same reason wallets
     * are: the set of (customer, currency) pairs is not knowable up front.
     */
    public Account ensureCustomerHold(String customerId, String currencyCode) {
        CurrencyUnit currency = currencies.require(currencyCode);
        String code = ChartOfAccounts.hold(customerId, currency.code());
        Account cached = byCode.get(code);
        if (cached != null) {
            return cached;
        }
        jdbc.sql("""
                INSERT INTO account
                    (code, name, currency_code, account_type, normal_side, is_contingent, allow_negative_balance)
                VALUES (?, ?, ?, 'LIABILITY', 'CREDIT', FALSE, FALSE)
                ON CONFLICT (code) DO NOTHING
                """)
                .params(code, "Customer hold " + customerId + " " + currency.code(), currency.code())
                .update();
        return require(code);
    }

    public static String customerWalletCode(String customerId, String currencyCode) {
        return ChartOfAccounts.wallet(customerId, currencyCode);
    }

    /** House account codes are derived, never typed at call sites. */
    public static String houseCode(String suffix, String currencyCode) {
        return suffix + ":" + currencyCode;
    }

    private Account map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                currencies.require(rs.getString("currency_code").trim()),
                AccountType.valueOf(rs.getString("account_type")),
                Direction.valueOf(rs.getString("normal_side")),
                rs.getBoolean("is_contingent"),
                rs.getBoolean("allow_negative_balance"));
    }
}
