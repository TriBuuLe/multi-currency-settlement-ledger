-- ---------------------------------------------------------------------------
-- Idempotency.
--
-- Every mutating endpoint requires a client-supplied Idempotency-Key. The
-- uniqueness of (scope, idempotency_key) is enforced by a primary key, so two
-- concurrent retries of the same request race on an INSERT and exactly one
-- wins. The loser either replays the stored response or, if the winner is
-- still in flight, is told to retry -- it never gets to post a second copy.
--
-- `request_hash` catches the dangerous case: the same key reused with a
-- different body. That is a client bug and is rejected with 422 rather than
-- silently returning someone else's result.
-- ---------------------------------------------------------------------------

CREATE TABLE idempotency_record (
    scope           TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('IN_FLIGHT', 'COMPLETED')),
    response_status INT,
    response_body   JSONB,
    -- Deliberately NOT a foreign key to journal_transaction.
    --
    -- This column is a reservation, not a reference: the claim is written and
    -- committed BEFORE the work runs, so that a concurrent retry can see it. At
    -- that moment the transaction does not exist yet, and if the work rolls back
    -- it never will. A foreign key here would make the whole scheme impossible --
    -- and the scheme is what lets the reaper ask the journal whether a crashed
    -- request's work actually landed.
    transaction_id  UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,

    PRIMARY KEY (scope, idempotency_key)
);

CREATE INDEX idempotency_in_flight_idx ON idempotency_record (created_at)
    WHERE status = 'IN_FLIGHT';
