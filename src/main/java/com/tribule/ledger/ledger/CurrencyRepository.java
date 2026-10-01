package com.tribule.ledger.ledger;

import com.tribule.ledger.money.CurrencyUnit;
import com.tribule.ledger.money.MoneyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Currency metadata. Cached in memory because minor-unit scales are reference
 * data that does not change while the process is running, and every single
 * amount conversion needs one.
 */
@Repository
public class CurrencyRepository {

    private final JdbcClient jdbc;
    private final Map<String, CurrencyUnit> cache = new ConcurrentHashMap<>();

    public CurrencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public CurrencyUnit require(String code) {
        String normalized = code == null ? null : code.trim().toUpperCase();
        if (normalized == null || normalized.length() != 3) {
            throw new MoneyException("invalid currency code: " + code);
        }
        return cache.computeIfAbsent(normalized, c -> jdbc
                .sql("SELECT code, minor_unit_scale FROM currency WHERE code = ? AND is_active")
                .param(c)
                .query((rs, n) -> new CurrencyUnit(rs.getString("code").trim(), rs.getInt("minor_unit_scale")))
                .optional()
                .orElseThrow(() -> new MoneyException("unknown or inactive currency: " + c)));
    }

    public List<CurrencyUnit> findAllActive() {
        return jdbc.sql("SELECT code, minor_unit_scale FROM currency WHERE is_active ORDER BY code")
                .query((rs, n) -> new CurrencyUnit(rs.getString("code").trim(), rs.getInt("minor_unit_scale")))
                .list();
    }
}
