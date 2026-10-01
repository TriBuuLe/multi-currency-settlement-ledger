-- ---------------------------------------------------------------------------
-- Bitemporal FX rates.
--
-- Two independent time axes:
--
--   effective_at  valid time       -- when this price held in the market
--   observed_at   transaction time -- when we learned about it
--
-- Splitting them is what makes "what did we believe the EUR/USD rate was, as
-- of last Tuesday, using only what we knew at the time?" answerable. A vendor
-- that back-corrects a bad tick inserts a new row with the same effective_at
-- and a later observed_at; the wrong row is never edited, so any historical
-- computation stays reproducible.
-- ---------------------------------------------------------------------------

CREATE TABLE fx_rate (
    id             UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency  CHAR(3)        NOT NULL REFERENCES currency (code),
    quote_currency CHAR(3)        NOT NULL REFERENCES currency (code),
    -- 1 unit of base costs `rate` units of quote, in major units.
    rate           NUMERIC(28,14) NOT NULL CHECK (rate > 0),
    effective_at   TIMESTAMPTZ    NOT NULL,
    observed_at    TIMESTAMPTZ    NOT NULL,
    source         TEXT           NOT NULL DEFAULT 'manual',
    -- Set when this row was published to correct an earlier observation.
    supersedes_id  UUID           REFERENCES fx_rate (id),

    CONSTRAINT fx_rate_distinct_legs CHECK (base_currency <> quote_currency),
    CONSTRAINT fx_rate_bitemporal_uq UNIQUE (base_currency, quote_currency, effective_at, observed_at)
);

-- Supports the "latest effective, as known at" lookup in one index scan.
CREATE INDEX fx_rate_lookup_idx
    ON fx_rate (base_currency, quote_currency, effective_at DESC, observed_at DESC);

CREATE TRIGGER fx_rate_append_only
    BEFORE UPDATE OR DELETE ON fx_rate
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

ALTER TABLE journal_transaction
    ADD CONSTRAINT journal_transaction_fx_rate_fk
    FOREIGN KEY (fx_rate_id) REFERENCES fx_rate (id);
