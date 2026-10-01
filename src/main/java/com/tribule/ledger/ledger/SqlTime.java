package com.tribule.ledger.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Instant to timestamptz and back.
 *
 * <p>The driver is handed {@link OffsetDateTime} at UTC rather than
 * {@link Instant} or {@code java.sql.Timestamp}: the former is unambiguous
 * across driver versions, and the latter reinterprets values in the JVM's
 * default zone, which is how timestamps silently shift by hours in production.
 */
public final class SqlTime {

    private SqlTime() {
    }

    public static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant fromDb(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
