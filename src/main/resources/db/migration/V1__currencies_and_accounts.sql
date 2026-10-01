-- ---------------------------------------------------------------------------
-- Currencies and the chart of accounts.
--
-- Money is never stored as a float. Every amount in this schema is a BIGINT
-- count of minor units, and `minor_unit_scale` is the only place that says how
-- many minor units make one major unit. JPY is scale 0, USD is 2, KWD is 3.
-- ---------------------------------------------------------------------------

CREATE TABLE currency (
    code             CHAR(3)  PRIMARY KEY,
    name             TEXT     NOT NULL,
    minor_unit_scale SMALLINT NOT NULL CHECK (minor_unit_scale BETWEEN 0 AND 4),
    is_active        BOOLEAN  NOT NULL DEFAULT TRUE
);

INSERT INTO currency (code, name, minor_unit_scale) VALUES
    ('USD', 'United States dollar', 2),
    ('EUR', 'Euro',                 2),
    ('GBP', 'Pound sterling',       2),
    ('CHF', 'Swiss franc',          2),
    ('SGD', 'Singapore dollar',     2),
    ('JPY', 'Japanese yen',         0),
    ('KWD', 'Kuwaiti dinar',        3);

CREATE TABLE account (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    code          TEXT        NOT NULL UNIQUE,
    name          TEXT        NOT NULL,
    currency_code CHAR(3)     NOT NULL REFERENCES currency (code),
    account_type  TEXT        NOT NULL
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE', 'CONTINGENT')),
    -- The side on which this account naturally increases. Used only for
    -- presentation: stored balances are always signed debit-minus-credit.
    normal_side   TEXT        NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    -- Contingent accounts hold authorization commitments. They are real
    -- double-entry accounts but are excluded from the balance sheet.
    is_contingent BOOLEAN     NOT NULL DEFAULT FALSE,
    -- House and clearing accounts may legitimately go negative; a customer
    -- wallet may not. Enforced in the database (see V3) so that no code path,
    -- including a future one, can overdraw an account.
    allow_negative_balance BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Referenced by journal_entry so that an entry's currency is forced to
    -- match its account's currency by a foreign key rather than by app code.
    CONSTRAINT account_id_currency_uq UNIQUE (id, currency_code)
);

CREATE INDEX account_currency_idx ON account (currency_code);
