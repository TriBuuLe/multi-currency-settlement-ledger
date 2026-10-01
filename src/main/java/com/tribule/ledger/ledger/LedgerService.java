package com.tribule.ledger.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes transactions to the journal.
 *
 * <p>Balance is checked twice, deliberately. Here, in application code, so that
 * a caller gets a precise message naming the currency and the amount it is out
 * by; and again in the database, at COMMIT, by a constraint trigger that no code
 * path can bypass. The first check is a courtesy. The second one is the
 * guarantee -- it holds for a buggy future service, a migration script, or
 * somebody at a psql prompt.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final Timer postTimer;
    private final Counter entriesWritten;

    public LedgerService(AccountRepository accounts, JournalRepository journal, MeterRegistry registry) {
        this.accounts = accounts;
        this.journal = journal;
        this.postTimer = Timer.builder("ledger.post")
                .description("Latency of appending one transaction to the journal")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.entriesWritten = Counter.builder("ledger.entries.written")
                .description("Journal entries appended")
                .register(registry);
    }

    @Transactional
    public PostedTransaction post(PostingCommand command) {
        return postTimer.record(() -> doPost(command));
    }

    private PostedTransaction doPost(PostingCommand command) {
        if (command.postings().size() < 2) {
            throw new LedgerException.Unbalanced(
                    "a transaction needs at least two legs, got " + command.postings().size());
        }

        List<JournalRepository.ResolvedPosting> resolved = command.postings().stream()
                .map(p -> new JournalRepository.ResolvedPosting(
                        accounts.require(p.accountCode()), p.direction(), p.amountMinor(), p.memo()))
                .toList();

        assertBalancedPerCurrency(resolved);

        try {
            journal.insertTransaction(command);
            List<JournalEntry> entries = journal.insertEntries(command.id(), resolved);
            // Run the deferred triggers now, so failures are reported in terms of
            // the request rather than as an anonymous commit failure.
            journal.flushDeferredConstraints();
            entriesWritten.increment(entries.size());

            JournalTransaction transaction = journal.findTransaction(command.id())
                    .orElseThrow(() -> new LedgerException.TransactionNotFound(command.id()));
            return new PostedTransaction(transaction, entries);
        } catch (DuplicateKeyException e) {
            throw new LedgerException(
                    "transaction id %s has already been written".formatted(command.id()), e);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    /**
     * Reverses a transaction by posting its mirror image.
     *
     * <p>Nothing is edited or deleted. The original stays in the journal forever
     * and the correction sits next to it, which is the only way an auditor can
     * tell the difference between "this never happened" and "this happened and
     * was undone".
     */
    @Transactional
    public PostedTransaction reverse(UUID transactionId, String reason, Instant occurredAt) {
        JournalTransaction original = journal.findTransaction(transactionId)
                .orElseThrow(() -> new LedgerException.TransactionNotFound(transactionId));
        if (journal.isReversed(transactionId)) {
            throw new LedgerException.AlreadyReversed(transactionId);
        }

        List<JournalEntry> entries = journal.findEntriesByTransaction(transactionId);
        PostingCommand.Builder builder = PostingCommand.of(TransactionKind.REVERSAL, occurredAt)
                .reverses(transactionId)
                .reference(original.reference())
                .description("reversal of " + transactionId + ": " + reason)
                .fxRate(original.fxRateId())
                .correlationId(original.correlationId());

        for (JournalEntry entry : entries) {
            builder.posting(new Posting(
                    entry.accountCode(),
                    entry.direction().opposite(),
                    entry.amountMinor(),
                    "reverses entry " + entry.id()));
        }
        return post(builder.build());
    }

    @Transactional(readOnly = true)
    public PostedTransaction require(UUID transactionId) {
        JournalTransaction transaction = journal.findTransaction(transactionId)
                .orElseThrow(() -> new LedgerException.TransactionNotFound(transactionId));
        return new PostedTransaction(transaction, journal.findEntriesByTransaction(transactionId));
    }

    private void assertBalancedPerCurrency(List<JournalRepository.ResolvedPosting> postings) {
        Map<String, Long> netByCurrency = new LinkedHashMap<>();
        for (JournalRepository.ResolvedPosting posting : postings) {
            netByCurrency.merge(
                    posting.account().currency().code(),
                    (long) posting.direction().signum() * posting.amountMinor(),
                    Math::addExact);
        }
        for (Map.Entry<String, Long> currency : netByCurrency.entrySet()) {
            if (currency.getValue() != 0L) {
                throw new LedgerException.Unbalanced(
                        "transaction does not balance in %s: debits exceed credits by %d minor units"
                                .formatted(currency.getKey(), currency.getValue()));
            }
        }
    }

    private LedgerException translate(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        if (message == null) {
            return new LedgerException("ledger write rejected by the database", e);
        }
        if (message.contains("insufficient funds")) {
            return new LedgerException.InsufficientFunds(firstLine(message), e);
        }
        if (message.contains("unbalanced transaction") || message.contains("fewer than two entries")) {
            return new LedgerException.Unbalanced(firstLine(message));
        }
        if (message.contains("append-only")) {
            return new LedgerException(firstLine(message), e);
        }
        return new LedgerException("ledger write rejected: " + firstLine(message), e);
    }

    private static String firstLine(String message) {
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
