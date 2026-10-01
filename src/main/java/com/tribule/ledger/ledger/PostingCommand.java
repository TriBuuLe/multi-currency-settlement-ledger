package com.tribule.ledger.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A request to write one transaction to the journal.
 *
 * <p>{@code occurredAt} is when the economic event happened and is supplied by
 * the caller; the journal records its own write time separately. Keeping the two
 * apart is what makes backdated corrections expressible without lying about when
 * they were booked.
 */
public record PostingCommand(
        UUID id,
        TransactionKind kind,
        String reference,
        String description,
        Instant occurredAt,
        UUID reversesTransactionId,
        UUID fxRateId,
        String correlationId,
        List<Posting> postings) {

    public PostingCommand {
        postings = List.copyOf(postings);
    }

    public static Builder of(TransactionKind kind, Instant occurredAt) {
        return new Builder(kind, occurredAt);
    }

    public static final class Builder {
        private final TransactionKind kind;
        private final Instant occurredAt;
        private final List<Posting> postings = new ArrayList<>();
        private UUID id = UUID.randomUUID();
        private String reference;
        private String description;
        private UUID reversesTransactionId;
        private UUID fxRateId;
        private String correlationId;

        private Builder(TransactionKind kind, Instant occurredAt) {
            this.kind = kind;
            this.occurredAt = occurredAt;
        }

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Builder reference(String value) {
            this.reference = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder reverses(UUID value) {
            this.reversesTransactionId = value;
            return this;
        }

        public Builder fxRate(UUID value) {
            this.fxRateId = value;
            return this;
        }

        public Builder correlationId(String value) {
            this.correlationId = value;
            return this;
        }

        public Builder debit(String accountCode, long amountMinor, String memo) {
            postings.add(Posting.debit(accountCode, amountMinor, memo));
            return this;
        }

        public Builder credit(String accountCode, long amountMinor, String memo) {
            postings.add(Posting.credit(accountCode, amountMinor, memo));
            return this;
        }

        /**
         * Posts a signed amount: a debit when positive, a credit when negative,
         * and nothing at all when zero.
         *
         * <p>FX settlement is full of legs whose direction depends on which way
         * the rate moved. Writing those as an if/else per leg is where sign
         * errors come from; deriving the side from the sign keeps the algebra in
         * one place and lets a zero-value leg disappear instead of being posted
         * as a meaningless zero.
         */
        public Builder signed(String accountCode, long signedAmountMinor, String memo) {
            if (signedAmountMinor > 0) {
                return debit(accountCode, signedAmountMinor, memo);
            }
            if (signedAmountMinor < 0) {
                return credit(accountCode, -signedAmountMinor, memo);
            }
            return this;
        }

        public Builder posting(Posting posting) {
            postings.add(posting);
            return this;
        }

        public PostingCommand build() {
            return new PostingCommand(id, kind, reference, description, occurredAt,
                    reversesTransactionId, fxRateId, correlationId, postings);
        }
    }
}
