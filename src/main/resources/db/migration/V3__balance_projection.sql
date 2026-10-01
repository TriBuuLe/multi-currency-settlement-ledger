-- ---------------------------------------------------------------------------
-- Balances are a projection of the journal, not a source of truth.
--
-- `balance_minor` is signed debit-minus-credit. That convention buys one very
-- strong global invariant, asserted by the verifier and exported as a metric:
--
--     for every currency:  SUM(balance_minor) over all accounts = 0
--
-- The projection is maintained by a trigger inside the same transaction as the
-- entry, so it cannot drift from the journal without the write failing. The
-- verifier still recomputes it from scratch, because "cannot drift" is a claim
-- that deserves a test rather than a comment.
--
-- The UPSERT below is also the concurrency control for an account. Two
-- transactions posting to the same account contend on the same
-- `account_balance` row, so Postgres serialises them on a row lock and the
-- second one sees the first one's balance. That is why the overdraft check is
-- correct under plain READ COMMITTED and does not need SERIALIZABLE.
-- ---------------------------------------------------------------------------

CREATE TABLE account_balance (
    account_id    UUID        PRIMARY KEY REFERENCES account (id),
    currency_code CHAR(3)     NOT NULL REFERENCES currency (code),
    balance_minor BIGINT      NOT NULL DEFAULT 0,
    entry_count   BIGINT      NOT NULL DEFAULT 0,
    last_entry_id BIGINT,
    version       BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX account_balance_currency_idx ON account_balance (currency_code);

-- Checkpoints, so rebuilding a balance is O(entries since snapshot) rather
-- than O(all entries ever).
CREATE TABLE balance_snapshot (
    id             BIGSERIAL   PRIMARY KEY,
    account_id     UUID        NOT NULL REFERENCES account (id),
    up_to_entry_id BIGINT      NOT NULL,
    balance_minor  BIGINT      NOT NULL,
    entry_count    BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT balance_snapshot_uq UNIQUE (account_id, up_to_entry_id)
);

CREATE INDEX balance_snapshot_lookup_idx ON balance_snapshot (account_id, up_to_entry_id DESC);

CREATE FUNCTION apply_entry_to_balance() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    signed_delta BIGINT;
BEGIN
    signed_delta := CASE WHEN NEW.direction = 'DEBIT' THEN NEW.amount_minor ELSE -NEW.amount_minor END;

    INSERT INTO account_balance AS b
        (account_id, currency_code, balance_minor, entry_count, last_entry_id, version, updated_at)
    VALUES
        (NEW.account_id, NEW.currency_code, signed_delta, 1, NEW.id, 1, now())
    ON CONFLICT (account_id) DO UPDATE
        SET balance_minor = b.balance_minor + signed_delta,
            entry_count   = b.entry_count + 1,
            last_entry_id  = GREATEST(COALESCE(b.last_entry_id, 0), NEW.id),
            version       = b.version + 1,
            updated_at    = now();

    RETURN NULL;
END;
$$;

CREATE TRIGGER journal_entry_project_balance
    AFTER INSERT ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION apply_entry_to_balance();

-- --------------------------------------------------------------------------
-- Invariant 3: accounts that are not allowed to go negative, do not.
--
-- Deferred to COMMIT, so the order of legs inside one transaction does not
-- matter -- only the state it leaves behind. The row lock taken by the
-- projection UPSERT above is still held at that point, so a concurrent
-- transfer cannot slip between the check and the commit.
-- --------------------------------------------------------------------------
CREATE FUNCTION assert_balance_not_negative() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    acct           RECORD;
    normal_balance BIGINT;
BEGIN
    SELECT a.code, a.normal_side, a.allow_negative_balance, b.balance_minor
      INTO acct
      FROM account a
      JOIN account_balance b ON b.account_id = a.id
     WHERE a.id = NEW.account_id;

    IF NOT FOUND OR acct.allow_negative_balance THEN
        RETURN NULL;
    END IF;

    normal_balance := CASE WHEN acct.normal_side = 'DEBIT' THEN acct.balance_minor ELSE -acct.balance_minor END;

    IF normal_balance < 0 THEN
        RAISE EXCEPTION 'insufficient funds in %: balance would be % minor units',
            acct.code, normal_balance
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entry_no_overdraft
    AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_balance_not_negative();
