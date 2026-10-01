package com.tribule.ledger.recon;

import com.tribule.ledger.ledger.Direction;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a bank statement export.
 *
 * <p>Format: {@code external_ref,posted_at,currency,amount_minor,direction,description}
 * with a header row. {@code posted_at} is an ISO-8601 instant or a plain date;
 * {@code direction} is DEBIT when our balance went up.
 *
 * <p>Amounts arrive already in minor units, deliberately. Accepting "1,234.56"
 * from an upstream file means guessing a locale and a scale on every row, and a
 * statement parser that guesses is a statement parser that will one day read a
 * European decimal comma as a thousands separator.
 */
@Component
public class StatementCsvParser {

    /** A row as read from the file, before it has an identity in the database. */
    public record ParsedLine(int lineNumber, String externalRef, Instant postedAt, String currencyCode,
                             long amountMinor, Direction direction, String description) {
    }

    private static final int COLUMNS = 6;

    public List<ParsedLine> parse(Reader source) {
        List<ParsedLine> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(source)) {
            String header = reader.readLine();
            if (header == null) {
                throw new ReconciliationException.MalformedStatement(0, "the file is empty");
            }
            if (!header.toLowerCase().startsWith("external_ref")) {
                throw new ReconciliationException.MalformedStatement(1,
                        "expected a header starting with 'external_ref', got: " + header);
            }

            String raw;
            int lineNumber = 1;
            while ((raw = reader.readLine()) != null) {
                lineNumber++;
                if (raw.isBlank()) {
                    continue;
                }
                lines.add(parseLine(lineNumber, raw));
            }
        } catch (IOException e) {
            throw new ReconciliationException("could not read the statement: " + e.getMessage());
        }
        if (lines.isEmpty()) {
            throw new ReconciliationException.MalformedStatement(1, "the file has a header but no rows");
        }
        return lines;
    }

    private ParsedLine parseLine(int lineNumber, String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length < COLUMNS) {
            throw new ReconciliationException.MalformedStatement(lineNumber,
                    "expected %d fields, found %d".formatted(COLUMNS, fields.length));
        }
        String externalRef = fields[0].trim();
        if (externalRef.isEmpty()) {
            throw new ReconciliationException.MalformedStatement(lineNumber, "external_ref is blank");
        }
        try {
            return new ParsedLine(
                    lineNumber,
                    externalRef,
                    parseInstant(fields[1].trim()),
                    fields[2].trim().toUpperCase(),
                    Long.parseLong(fields[3].trim()),
                    Direction.valueOf(fields[4].trim().toUpperCase()),
                    fields[5].trim());
        } catch (NumberFormatException e) {
            throw new ReconciliationException.MalformedStatement(lineNumber,
                    "amount_minor is not a whole number: " + fields[3]);
        } catch (IllegalArgumentException e) {
            throw new ReconciliationException.MalformedStatement(lineNumber,
                    "direction must be DEBIT or CREDIT, got: " + fields[4]);
        } catch (DateTimeParseException e) {
            throw new ReconciliationException.MalformedStatement(lineNumber,
                    "posted_at is not an ISO-8601 instant or date: " + fields[1]);
        }
    }

    private Instant parseInstant(String value) {
        if (value.length() == 10) {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return Instant.parse(value);
    }
}
