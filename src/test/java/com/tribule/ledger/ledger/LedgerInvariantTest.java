package com.tribule.ledger.ledger;

import com.tribule.ledger.support.AbstractLedgerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The invariants the database enforces, tested by trying to violate them.
 *
 * <p>These tests deliberately go around the service layer where they can, using raw
 * SQL. The claim being verified is not "the application checks this" -- it is "the
 * database refuses this", which is the only version of the claim that survives a
 * buggy future service, a migration script, or somebody at a psql prompt.
 */
class LedgerInvariantTest extends AbstractLedgerTest {

    @Test
    @DisplayName("the application rejects an unbalanced transaction before it reaches the database")
    void applicationRejectsUnbalanced() {
        assertThatThrownBy(() -> ledger.post(PostingCommand.of(TransactionKind.TRANSFER, Instant.now())
                .debit(ChartOfAccounts.nostro("USD"), 1000, "out of thin air")
                .credit(ChartOfAccounts.suspense("USD"), 999, "not enough")
                .build()))
                .isInstanceOf(LedgerException.Unbalanced.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("1");
    }

    @Test
    @DisplayName("the database rejects an unbalanced transaction even when the service is bypassed")
    void databaseRejectsUnbalanced() {
        UUID transactionId = UUID.randomUUID();
        UUID nostro = accountId(ChartOfAccounts.nostro("USD"));
        UUID suspense = accountId(ChartOfAccounts.suspense("USD"));

        assertThatThrownBy(() -> jdbc.sql("""
                WITH t AS (
                    INSERT INTO journal_transaction (id, kind, occurred_at)
                    VALUES (?, 'TRANSFER', now()) RETURNING id
                )
                INSERT INTO journal_entry (transaction_id, account_id, currency_code, direction, amount_minor, entry_seq)
                SELECT t.id, ?, 'USD', 'DEBIT',  1000, 1 FROM t
                UNION ALL
                SELECT t.id, ?, 'USD', 'CREDIT',  999, 2 FROM t
                """).params(transactionId, nostro, suspense).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("unbalanced transaction");

        assertThat(transactionExists(transactionId))
                .as("the rejected transaction must leave nothing behind")
                .isFalse();
    }

    @Test
    @DisplayName("a transaction may be unbalanced mid-flight and must balance at commit")
    void balanceIsCheckedAtCommitNotPerRow() {
        // The constraint is DEFERRABLE INITIALLY DEFERRED, so the first leg can be
        // written on its own. If it were checked per row, no two-leg transaction
        // could ever be inserted at all.
        String reference = newReference("deferred");
        PostedTransaction posted = ledger.post(PostingCommand.of(TransactionKind.TRANSFER, Instant.now())
                .reference(reference)
                .debit(ChartOfAccounts.nostro("USD"), 5000, "first leg")
                .credit(ChartOfAccounts.suspense("USD"), 5000, "second leg")
                .build());

        assertThat(posted.entries()).hasSize(2);
        assertBooksBalance();
    }

    @Test
    @DisplayName("the journal is append-only: entries cannot be updated or deleted")
    void journalIsAppendOnly() {
        PostedTransaction posted = ledger.post(PostingCommand.of(TransactionKind.TRANSFER, Instant.now())
                .reference(newReference("immutable"))
                .debit(ChartOfAccounts.nostro("EUR"), 2500, "in")
                .credit(ChartOfAccounts.suspense("EUR"), 2500, "out")
                .build());
        long entryId = posted.entries().getFirst().id();

        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_entry SET amount_minor = 1 WHERE id = ?")
                .param(entryId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entry WHERE id = ?")
                .param(entryId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_transaction WHERE id = ?")
                .param(posted.transaction().id()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("an entry's currency cannot differ from its account's, enforced by a composite foreign key")
    void entryCurrencyMustMatchAccount() {
        UUID transactionId = UUID.randomUUID();
        UUID usdAccount = accountId(ChartOfAccounts.nostro("USD"));

        assertThatThrownBy(() -> jdbc.sql("""
                WITH t AS (
                    INSERT INTO journal_transaction (id, kind, occurred_at)
                    VALUES (?, 'TRANSFER', now()) RETURNING id
                )
                INSERT INTO journal_entry (transaction_id, account_id, currency_code, direction, amount_minor, entry_seq)
                SELECT t.id, ?, 'JPY', 'DEBIT', 100, 1 FROM t
                """).params(transactionId, usdAccount).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("journal_entry_account_currency_fk");
    }

    @Test
    @DisplayName("a negative or zero amount cannot be posted; sign belongs to the direction")
    void amountsAreAlwaysPositive() {
        assertThatThrownBy(() -> Posting.debit(ChartOfAccounts.nostro("USD"), -100, "negative"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");

        UUID transactionId = UUID.randomUUID();
        UUID nostro = accountId(ChartOfAccounts.nostro("USD"));
        assertThatThrownBy(() -> jdbc.sql("""
                WITH t AS (
                    INSERT INTO journal_transaction (id, kind, occurred_at)
                    VALUES (?, 'TRANSFER', now()) RETURNING id
                )
                INSERT INTO journal_entry (transaction_id, account_id, currency_code, direction, amount_minor, entry_seq)
                SELECT t.id, ?, 'USD', 'DEBIT', -100, 1 FROM t
                """).params(transactionId, nostro).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a wallet cannot be overdrawn, and the error names the account")
    void walletCannotGoNegative() {
        String customer = newCustomer("overdraft");
        fund(customer, "USD", 10_000);

        assertThatThrownBy(() -> transfers.payout(customer, "USD", 10_001,
                newReference("too-much"), Instant.now(), UUID.randomUUID()))
                .isInstanceOf(LedgerException.InsufficientFunds.class)
                .hasMessageContaining("insufficient funds")
                .hasMessageContaining(customer);

        assertThat(walletBalance(customer, "USD"))
                .as("the failed payout must leave the balance untouched")
                .isEqualTo(10_000);
        assertBooksBalance();
    }

    @Test
    @DisplayName("the overdraft check looks at the end state, not the order of the legs")
    void overdraftIsCheckedAtCommit() {
        // Debiting the wallet first would take it negative mid-transaction; the
        // credit that follows puts it back. Because the check is deferred to commit,
        // this is allowed -- and that is the right answer, since the transaction as a
        // whole never overdraws anything.
        String customer = newCustomer("ordering");
        fund(customer, "USD", 1_000);

        PostedTransaction posted = ledger.post(PostingCommand.of(TransactionKind.TRANSFER, Instant.now())
                .reference(newReference("order"))
                .debit(ChartOfAccounts.wallet(customer, "USD"), 5_000, "out first")
                .credit(ChartOfAccounts.wallet(customer, "USD"), 5_000, "and back in")
                .build());

        assertThat(posted.entries()).hasSize(2);
        assertThat(walletBalance(customer, "USD")).isEqualTo(1_000);
    }

    @Test
    @DisplayName("a reversal leaves the original in place and nets the balance to zero")
    void reversalIsAdditive() {
        String customer = newCustomer("reversal");
        fund(customer, "GBP", 7_500);
        long afterFunding = walletBalance(customer, "GBP");

        PostedTransaction payout = ledger.post(PostingCommand.of(TransactionKind.TRANSFER, Instant.now())
                .reference(newReference("payout"))
                .debit(ChartOfAccounts.wallet(customer, "GBP"), 2_500, "out")
                .credit(ChartOfAccounts.nostro("GBP"), 2_500, "sent")
                .build());
        assertThat(walletBalance(customer, "GBP")).isEqualTo(afterFunding - 2_500);

        PostedTransaction reversal = ledger.reverse(payout.transaction().id(), "sent in error", Instant.now());

        assertThat(reversal.transaction().reversesTransactionId()).isEqualTo(payout.transaction().id());
        assertThat(reversal.transaction().kind()).isEqualTo(TransactionKind.REVERSAL);
        assertThat(walletBalance(customer, "GBP")).isEqualTo(afterFunding);
        // The original is still there: "undone" is a different fact from "never happened".
        assertThat(ledger.require(payout.transaction().id()).entries()).hasSize(2);
        assertBooksBalance();
    }

    @Test
    @DisplayName("a transaction cannot be reversed twice")
    void reversalIsIdempotentByConstruction() {
        String customer = newCustomer("double-reversal");
        fund(customer, "USD", 3_000);
        PostedTransaction payout = ledger.post(PostingCommand.of(TransactionKind.TRANSFER, Instant.now())
                .reference(newReference("payout"))
                .debit(ChartOfAccounts.wallet(customer, "USD"), 1_000, "out")
                .credit(ChartOfAccounts.nostro("USD"), 1_000, "sent")
                .build());

        ledger.reverse(payout.transaction().id(), "first", Instant.now());

        assertThatThrownBy(() -> ledger.reverse(payout.transaction().id(), "second", Instant.now()))
                .isInstanceOf(LedgerException.AlreadyReversed.class);
    }

    @Test
    @DisplayName("a single-sided transaction is impossible")
    void singleLegIsRejected() {
        assertThatThrownBy(() -> ledger.post(PostingCommand.of(TransactionKind.TRANSFER, Instant.now())
                .debit(ChartOfAccounts.nostro("USD"), 100, "lonely")
                .build()))
                .isInstanceOf(LedgerException.Unbalanced.class)
                .hasMessageContaining("at least two legs");
    }

    private UUID accountId(String code) {
        return jdbc.sql("SELECT id FROM account WHERE code = ?").param(code).query(UUID.class).single();
    }

    private boolean transactionExists(UUID id) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM journal_transaction WHERE id = ?)")
                .param(id).query(Boolean.class).single());
    }
}
