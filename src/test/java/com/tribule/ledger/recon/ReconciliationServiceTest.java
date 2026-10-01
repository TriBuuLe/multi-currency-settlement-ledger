package com.tribule.ledger.recon;

import com.tribule.ledger.ledger.ChartOfAccounts;
import com.tribule.ledger.support.AbstractLedgerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Matching the ledger against the bank, and naming every difference.
 *
 * <p>The ledger is our opinion about what happened and the statement is the bank's.
 * Both are wrong sometimes and they are never in step, so the goal is not to force the
 * numbers to agree -- it is to account for every difference by name, and to act only on
 * the ones where the right action is unambiguous.
 *
 * <p>Each test works in its own week-long window, far from every other test's, because
 * the "what did we book that the bank has not reported?" scan is necessarily a date-range
 * query over shared data.
 */
class ReconciliationServiceTest extends AbstractLedgerTest {

    @Autowired private ReconciliationService reconciliation;

    @Test
    @DisplayName("every class of break is detected and classified")
    void classifiesEveryBreakType() {
        Instant windowDay = nextTimeline();
        LocalDate asOfDate = LocalDate.ofInstant(windowDay, ZoneOffset.UTC);
        Instant bookedAt = windowDay.plus(Duration.ofHours(10));
        Instant bookedLate = windowDay.minus(Duration.ofDays(5));
        String tag = newReference("rec");

        // What the ledger believes happened.
        bookNostroReceipt(tag + "-MATCH-1", "GBP", 10_000, bookedAt);
        bookNostroReceipt(tag + "-MATCH-2", "GBP", 20_000, bookedAt);
        bookNostroReceipt(tag + "-WRONG-AMT", "GBP", 5_000, bookedAt);
        bookNostroReceipt(tag + "-LEDGER-ONLY", "GBP", 7_000, bookedAt);
        bookNostroReceipt(tag + "-LATE", "GBP", 3_000, bookedLate);

        long suspenseBefore = houseBalance(ChartOfAccounts.suspense("GBP"));

        // What the bank says happened. DEBIT means our balance went up.
        String csv = """
                external_ref,posted_at,currency,amount_minor,direction,description
                %1$s-MATCH-1,%2$s,GBP,10000,DEBIT,clean match
                %1$s-MATCH-2,%2$s,GBP,20000,DEBIT,first occurrence
                %1$s-MATCH-2,%2$s,GBP,20000,DEBIT,the bank sent this twice
                %1$s-WRONG-AMT,%2$s,GBP,5500,DEBIT,bank says 5500 we say 5000
                %1$s-BANK-ONLY,%2$s,GBP,4000,DEBIT,we never booked this
                %1$s-LATE,%2$s,GBP,3000,DEBIT,value dated five days after we booked it
                """.formatted(tag, bookedAt.toString());

        StatementBatch batch = reconciliation.importStatement(
                "test-bank", tag + ".csv", asOfDate, new StringReader(csv));
        assertThat(batch.lineCount()).isEqualTo(6);

        ReconciliationReport report = reconciliation.reconcile(batch.id());

        assertThat(report.statementLines()).isEqualTo(6);
        assertThat(report.matched()).isEqualTo(3);
        assertThat(report.breaksByType())
                .containsEntry(BreakType.DUPLICATE_IN_STATEMENT.name(), 1)
                .containsEntry(BreakType.AMOUNT_MISMATCH.name(), 1)
                .containsEntry(BreakType.MISSING_IN_LEDGER.name(), 1)
                .containsEntry(BreakType.TIMING_DIFFERENCE.name(), 1)
                .containsEntry(BreakType.MISSING_IN_STATEMENT.name(), 1);
        assertThat(report.breaksDetected()).isEqualTo(5);

        // Three of the five carry enough information to act on without a person.
        assertThat(report.autoResolved()).isEqualTo(3);
        assertThat(report.open()).isEqualTo(2);
        assertThat(report.autoResolutionRate()).isEqualTo(0.6);

        // The two left open are the two that need a decision, not a rule.
        assertThat(report.openBreaks()).extracting(ReconciliationBreak::breakType)
                .containsExactlyInAnyOrder(BreakType.AMOUNT_MISMATCH, BreakType.MISSING_IN_STATEMENT);

        // The unexplained bank credit was booked so the books agree with the bank,
        // and the suspense balance is now the open item.
        assertThat(report.adjustmentTransactions()).hasSize(1);
        assertThat(houseBalance(ChartOfAccounts.suspense("GBP")) - suspenseBefore).isEqualTo(-4_000);
        assertThat(report.notes()).anyMatch(note -> note.contains("suspense is not flat"));
        assertBooksBalance();
    }

    @Test
    @DisplayName("the amount mismatch records which side is out and by how much")
    void amountMismatchRecordsTheDelta() {
        Instant windowDay = nextTimeline();
        LocalDate asOfDate = LocalDate.ofInstant(windowDay, ZoneOffset.UTC);
        Instant bookedAt = windowDay.plus(Duration.ofHours(6));
        String tag = newReference("delta");
        bookNostroReceipt(tag + "-FEE", "EUR", 100_000, bookedAt);

        String csv = """
                external_ref,posted_at,currency,amount_minor,direction,description
                %s-FEE,%s,EUR,99750,DEBIT,bank deducted a 2.50 fee
                """.formatted(tag, bookedAt.toString());

        StatementBatch batch = reconciliation.importStatement(
                "test-bank", tag + ".csv", asOfDate, new StringReader(csv));
        ReconciliationReport report = reconciliation.reconcile(batch.id());

        ReconciliationBreak mismatch = report.openBreaks().stream()
                .filter(b -> b.breakType() == BreakType.AMOUNT_MISMATCH)
                .findFirst()
                .orElseThrow();

        assertThat(mismatch.deltaMinor())
                .as("ledger minus statement: we booked 250 minor units more than the bank paid")
                .isEqualTo(250);
        assertThat(mismatch.status()).isEqualTo(BreakStatus.OPEN);
        assertThat(mismatch.resolution()).contains("ledger 100000").contains("statement 99750");
        // No adjustment was invented: posting a plug would make the report look clean
        // and the books wrong.
        assertThat(report.adjustmentTransactions()).isEmpty();
    }

    @Test
    @DisplayName("a reversed payment nets to zero and does not show up as two breaks")
    void reversedPaymentNetsOut() {
        Instant windowDay = nextTimeline();
        LocalDate asOfDate = LocalDate.ofInstant(windowDay, ZoneOffset.UTC);
        Instant bookedAt = windowDay.plus(Duration.ofHours(4));
        String tag = newReference("reversed");

        var receipt = bookNostroReceipt(tag + "-OOPS", "USD", 50_000, bookedAt);
        ledger.reverse(receipt, "booked in error", bookedAt.plus(Duration.ofHours(1)));

        // The bank never saw it, and the statement is empty for this window.
        String csv = """
                external_ref,posted_at,currency,amount_minor,direction,description
                %s-UNRELATED,%s,USD,1,DEBIT,a line so the file is not empty
                """.formatted(tag, bookedAt.toString());

        StatementBatch batch = reconciliation.importStatement(
                "test-bank", tag + ".csv", asOfDate, new StringReader(csv));
        ReconciliationReport report = reconciliation.reconcile(batch.id());

        // Movements summed per reference, so a booking and its reversal cancel rather
        // than appearing as two unexplained differences.
        assertThat(report.openBreaks())
                .as("a reversed booking is not a break; it nets to nothing")
                .noneMatch(b -> b.breakType() == BreakType.MISSING_IN_STATEMENT);
        assertBooksBalance();
    }

    @Test
    @DisplayName("re-importing the same file is refused rather than double-counted")
    void duplicateImportIsRefused() {
        Instant windowDay = nextTimeline();
        LocalDate asOfDate = LocalDate.ofInstant(windowDay, ZoneOffset.UTC);
        String tag = newReference("dupe");
        String csv = """
                external_ref,posted_at,currency,amount_minor,direction,description
                %s-A,%s,USD,100,DEBIT,only line
                """.formatted(tag, windowDay.toString());

        reconciliation.importStatement("test-bank", tag + ".csv", asOfDate, new StringReader(csv));

        assertThatThrownBy(() -> reconciliation.importStatement(
                "test-bank", tag + ".csv", asOfDate, new StringReader(csv)))
                .isInstanceOf(ReconciliationException.DuplicateBatch.class)
                .hasMessageContaining("already been imported");
    }

    @Test
    @DisplayName("a malformed statement is rejected with the offending line number")
    void malformedStatementIsRejected() {
        LocalDate asOfDate = LocalDate.ofInstant(nextTimeline(), ZoneOffset.UTC);

        assertThatThrownBy(() -> reconciliation.importStatement("test-bank", "bad-header.csv", asOfDate,
                new StringReader("ref,date,ccy\nA,2024-01-01,USD\n")))
                .isInstanceOf(ReconciliationException.MalformedStatement.class)
                .hasMessageContaining("external_ref");

        assertThatThrownBy(() -> reconciliation.importStatement("test-bank", "bad-amount.csv", asOfDate,
                new StringReader("""
                        external_ref,posted_at,currency,amount_minor,direction,description
                        A,2024-01-01,USD,12.34,DEBIT,decimals are not minor units
                        """)))
                .isInstanceOf(ReconciliationException.MalformedStatement.class)
                .hasMessageContaining("not a whole number");

        assertThatThrownBy(() -> reconciliation.importStatement("test-bank", "bad-direction.csv", asOfDate,
                new StringReader("""
                        external_ref,posted_at,currency,amount_minor,direction,description
                        A,2024-01-01,USD,100,SIDEWAYS,not a direction
                        """)))
                .isInstanceOf(ReconciliationException.MalformedStatement.class)
                .hasMessageContaining("DEBIT or CREDIT");

        assertThatThrownBy(() -> reconciliation.importStatement("test-bank", "empty.csv", asOfDate,
                new StringReader("external_ref,posted_at,currency,amount_minor,direction,description\n")))
                .isInstanceOf(ReconciliationException.MalformedStatement.class)
                .hasMessageContaining("no rows");
    }

    @Test
    @DisplayName("a clean statement produces no breaks and a 100% resolution rate")
    void cleanStatementHasNoBreaks() {
        Instant windowDay = nextTimeline();
        LocalDate asOfDate = LocalDate.ofInstant(windowDay, ZoneOffset.UTC);
        Instant bookedAt = windowDay.plus(Duration.ofHours(2));
        String tag = newReference("clean");
        bookNostroReceipt(tag + "-ONE", "JPY", 150_000, bookedAt);
        bookNostroReceipt(tag + "-TWO", "JPY", 250_000, bookedAt);

        String csv = """
                external_ref,posted_at,currency,amount_minor,direction,description
                %1$s-ONE,%2$s,JPY,150000,DEBIT,clean
                %1$s-TWO,%2$s,JPY,250000,DEBIT,clean
                """.formatted(tag, bookedAt.toString());

        StatementBatch batch = reconciliation.importStatement(
                "test-bank", tag + ".csv", asOfDate, new StringReader(csv));
        ReconciliationReport report = reconciliation.reconcile(batch.id());

        assertThat(report.matched()).isEqualTo(2);
        assertThat(report.breaksDetected()).isZero();
        assertThat(report.autoResolutionRate()).isEqualTo(1.0);
        assertThat(report.adjustmentTransactions()).isEmpty();
    }

    /** Books a receipt into the nostro account at a controlled instant, so the window is predictable. */
    private UUID bookNostroReceipt(String reference, String currency, long amountMinor, Instant occurredAt) {
        String customer = newCustomer("recon");
        return transfers.fund(customer, currency, amountMinor, reference, occurredAt, UUID.randomUUID())
                .transaction().transaction().id();
    }
}
