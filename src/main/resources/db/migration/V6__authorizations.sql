-- ---------------------------------------------------------------------------
-- FX authorizations: the window where settlement risk actually lives.
--
-- An authorization is a promise: "we will give you `quoted_buy_amount_minor` of
-- the buy currency for `sell_amount_minor` of the sell currency". It is priced
-- at `quoted_rate` now and settled at some later rate. Between those two
-- moments the house carries the difference -- that is the exposure the risk
-- endpoints measure.
--
-- On authorization, nothing moves on the balance sheet. Instead the commitment
-- is posted to contingent accounts, so the obligation is on the books (and
-- balances, per currency, like everything else) without pretending cash has
-- moved. On settlement the hold is reversed and the rate difference is
-- realized into a dedicated FX P&L account, which is what keeps double-entry
-- intact across a currency conversion.
-- ---------------------------------------------------------------------------

CREATE TABLE fx_authorization (
    id                        UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    reference                 TEXT           NOT NULL UNIQUE,
    customer_id               TEXT           NOT NULL,
    sell_currency             CHAR(3)        NOT NULL REFERENCES currency (code),
    buy_currency              CHAR(3)        NOT NULL REFERENCES currency (code),
    sell_amount_minor         BIGINT         NOT NULL CHECK (sell_amount_minor > 0),
    quoted_buy_amount_minor   BIGINT         NOT NULL CHECK (quoted_buy_amount_minor > 0),
    quoted_rate               NUMERIC(28,14) NOT NULL CHECK (quoted_rate > 0),
    quoted_rate_id            UUID           NOT NULL REFERENCES fx_rate (id),
    status                    TEXT           NOT NULL
        CHECK (status IN ('PENDING', 'SETTLED', 'RELEASED', 'EXPIRED')),
    authorized_at             TIMESTAMPTZ    NOT NULL,
    expires_at                TIMESTAMPTZ    NOT NULL,
    settled_at                TIMESTAMPTZ,
    hold_transaction_id       UUID           NOT NULL REFERENCES journal_transaction (id),
    -- Deferrable, and for a specific reason: settlement claims this row BEFORE it
    -- writes the journal transaction, so that a request which has lost the race
    -- fails on the version check instead of wasting work and then reporting a
    -- confusing downstream error. That ordering means this column briefly
    -- references a transaction that does not exist yet, which only a deferred
    -- foreign key allows. It is still checked before the transaction commits.
    settlement_transaction_id UUID           REFERENCES journal_transaction (id)
                                                 DEFERRABLE INITIALLY DEFERRED,
    settlement_rate           NUMERIC(28,14),
    settlement_rate_id        UUID           REFERENCES fx_rate (id),
    -- Positive = the house gained, negative = the house absorbed the move.
    realized_pnl_minor        BIGINT,
    realized_pnl_currency     CHAR(3)        REFERENCES currency (code),
    version                   INT            NOT NULL DEFAULT 1,

    CONSTRAINT fx_authorization_distinct_legs CHECK (sell_currency <> buy_currency),
    CONSTRAINT fx_authorization_expiry       CHECK (expires_at > authorized_at),
    -- A terminal state must carry its evidence; a pending one must not.
    CONSTRAINT fx_authorization_settled_shape CHECK (
        (status <> 'SETTLED')
        OR (settlement_transaction_id IS NOT NULL
            AND settlement_rate IS NOT NULL
            AND realized_pnl_minor IS NOT NULL
            AND settled_at IS NOT NULL)
    )
);

CREATE INDEX fx_authorization_pending_idx ON fx_authorization (sell_currency, buy_currency)
    WHERE status = 'PENDING';
CREATE INDEX fx_authorization_expiry_idx  ON fx_authorization (expires_at)
    WHERE status = 'PENDING';
CREATE INDEX fx_authorization_customer_idx ON fx_authorization (customer_id, authorized_at DESC);
