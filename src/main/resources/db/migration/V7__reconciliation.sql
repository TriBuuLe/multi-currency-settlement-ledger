-- ---------------------------------------------------------------------------
-- Reconciliation.
--
-- The ledger is our opinion; the bank statement is theirs. Reconciliation is
-- the process of finding every place those two disagree and naming the reason.
-- Breaks are classified rather than merely counted, because "we are out by
-- 40 USD" is not actionable and "this line is in the statement twice" is.
-- ---------------------------------------------------------------------------

CREATE TABLE statement_batch (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source      TEXT        NOT NULL,
    filename    TEXT        NOT NULL,
    as_of_date  DATE        NOT NULL,
    line_count  INT         NOT NULL DEFAULT 0,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT statement_batch_uq UNIQUE (source, filename)
);

CREATE TABLE statement_line (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id      UUID        NOT NULL REFERENCES statement_batch (id),
    line_number   INT         NOT NULL,
    external_ref  TEXT        NOT NULL,
    posted_at     TIMESTAMPTZ NOT NULL,
    currency_code CHAR(3)     NOT NULL REFERENCES currency (code),
    amount_minor  BIGINT      NOT NULL CHECK (amount_minor > 0),
    direction     TEXT        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    description   TEXT,
    match_status  TEXT        NOT NULL DEFAULT 'UNMATCHED'
        CHECK (match_status IN ('UNMATCHED', 'MATCHED', 'BROKEN')),
    matched_transaction_id UUID REFERENCES journal_transaction (id),
    CONSTRAINT statement_line_uq UNIQUE (batch_id, line_number)
);

CREATE INDEX statement_line_ref_idx    ON statement_line (external_ref);
CREATE INDEX statement_line_batch_idx  ON statement_line (batch_id, match_status);

CREATE TABLE reconciliation_break (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id       UUID        NOT NULL REFERENCES statement_batch (id),
    statement_line_id UUID     REFERENCES statement_line (id),
    transaction_id UUID        REFERENCES journal_transaction (id),
    break_type     TEXT        NOT NULL CHECK (break_type IN (
                        'MISSING_IN_LEDGER',      -- bank saw it, we did not
                        'MISSING_IN_STATEMENT',   -- we booked it, bank has not
                        'AMOUNT_MISMATCH',        -- same ref, different money
                        'DUPLICATE_IN_STATEMENT', -- bank sent the ref twice
                        'TIMING_DIFFERENCE')),    -- matched, but posted late
    currency_code  CHAR(3)     REFERENCES currency (code),
    delta_minor    BIGINT      NOT NULL DEFAULT 0,
    status         TEXT        NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'AUTO_RESOLVED', 'MANUALLY_RESOLVED')),
    resolution     TEXT,
    adjustment_transaction_id UUID REFERENCES journal_transaction (id),
    detected_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMPTZ,

    CONSTRAINT reconciliation_break_anchored CHECK (
        statement_line_id IS NOT NULL OR transaction_id IS NOT NULL
    )
);

CREATE INDEX reconciliation_break_open_idx ON reconciliation_break (batch_id, break_type)
    WHERE status = 'OPEN';
