-- ---------------------------------------------------------------------------
-- The journal: an append-only, double-entry record of everything that happened.
--
-- Two invariants are enforced by the database itself, not by application code,
-- so that a bug in the service cannot create or destroy money:
--
--   1. Per transaction, per currency, sum(debits) = sum(credits).
--      Checked by a DEFERRABLE INITIALLY DEFERRED constraint trigger, so a
--      transaction may be legally unbalanced mid-flight and must be balanced
--      at COMMIT.
--   2. Rows are never updated or deleted. Mistakes are corrected by posting a
--      reversing transaction, which leaves the original visible forever.
-- ---------------------------------------------------------------------------

CREATE TABLE journal_transaction (
    id                      UUID        PRIMARY KEY,
    kind                    TEXT        NOT NULL CHECK (kind IN (
                                'FUNDING', 'TRANSFER', 'FX_CONVERSION',
                                'AUTHORIZATION_HOLD', 'HOLD_RELEASE', 'SETTLEMENT',
                                'REVERSAL', 'RATE_CORRECTION_ADJUSTMENT',
                                'RECON_ADJUSTMENT')),
    reference               TEXT,
    description             TEXT,
    -- Valid time: when the economic event happened.
    occurred_at             TIMESTAMPTZ NOT NULL,
    -- Transaction time: when we wrote it down. Never equal by construction.
    recorded_at             TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    reverses_transaction_id UUID        REFERENCES journal_transaction (id),
    fx_rate_id              UUID,   -- FK added in V5, once fx_rate exists
    correlation_id          TEXT
);

CREATE INDEX journal_transaction_occurred_idx  ON journal_transaction (occurred_at);
CREATE INDEX journal_transaction_kind_idx      ON journal_transaction (kind, occurred_at);
CREATE INDEX journal_transaction_reverses_idx  ON journal_transaction (reverses_transaction_id)
    WHERE reverses_transaction_id IS NOT NULL;

CREATE TABLE journal_entry (
    id             BIGSERIAL   PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES journal_transaction (id),
    account_id     UUID        NOT NULL,
    currency_code  CHAR(3)     NOT NULL,
    direction      TEXT        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- Always positive. Sign is carried by `direction`, never by the amount.
    amount_minor   BIGINT      NOT NULL CHECK (amount_minor > 0),
    entry_seq      SMALLINT    NOT NULL,
    memo           TEXT,

    CONSTRAINT journal_entry_account_currency_fk
        FOREIGN KEY (account_id, currency_code) REFERENCES account (id, currency_code),
    CONSTRAINT journal_entry_seq_uq UNIQUE (transaction_id, entry_seq)
);

CREATE INDEX journal_entry_account_idx     ON journal_entry (account_id, id);
CREATE INDEX journal_entry_transaction_idx ON journal_entry (transaction_id);
CREATE INDEX journal_entry_currency_idx    ON journal_entry (currency_code, id);

-- --------------------------------------------------------------------------
-- Invariant 1: every transaction balances, per currency, at COMMIT time.
-- --------------------------------------------------------------------------
CREATE FUNCTION assert_transaction_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    offending RECORD;
BEGIN
    SELECT e.currency_code                                                            AS currency_code,
           SUM(CASE WHEN e.direction = 'DEBIT'  THEN e.amount_minor ELSE 0 END)        AS debits,
           SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE 0 END)        AS credits
      INTO offending
      FROM journal_entry e
     WHERE e.transaction_id = NEW.transaction_id
     GROUP BY e.currency_code
    HAVING SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END) <> 0
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'unbalanced transaction % in %: debits=% credits=%',
            NEW.transaction_id, offending.currency_code, offending.debits, offending.credits
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- A single-sided transaction can never balance, so it is already rejected
    -- above; this guards the degenerate zero-entry case reached by other paths.
    IF (SELECT count(*) FROM journal_entry e WHERE e.transaction_id = NEW.transaction_id) < 2 THEN
        RAISE EXCEPTION 'transaction % has fewer than two entries', NEW.transaction_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();

-- --------------------------------------------------------------------------
-- Invariant 2: the journal is append-only.
-- --------------------------------------------------------------------------
CREATE FUNCTION forbid_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION
        '% is append-only: % is not permitted; post a reversing transaction instead',
        TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER journal_entry_append_only
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER journal_transaction_append_only
    BEFORE UPDATE OR DELETE ON journal_transaction
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER journal_entry_no_truncate
    BEFORE TRUNCATE ON journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
