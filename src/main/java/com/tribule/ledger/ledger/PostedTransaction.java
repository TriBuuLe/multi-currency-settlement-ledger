package com.tribule.ledger.ledger;

import java.util.List;

/** A transaction as it now exists in the journal. */
public record PostedTransaction(JournalTransaction transaction, List<JournalEntry> entries) {

    public PostedTransaction {
        entries = List.copyOf(entries);
    }
}
