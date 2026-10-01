-- ---------------------------------------------------------------------------
-- House chart of accounts, one set per active currency.
--
-- Generated from the currency table rather than typed out, so adding a currency
-- cannot leave a half-built chart behind. Customer wallet accounts are created
-- on demand at runtime (LIABILITY:CUSTOMER:<customerId>:<CCY>).
--
-- The accounts that matter for FX:
--
--   EQUITY:FX_POSITION      the pivot every conversion passes through. A
--                           conversion is two per-currency balanced halves
--                           joined here, which is how one transaction can move
--                           two currencies and still balance in each.
--   EQUITY:FX_REALIZED_PNL  where the authorization-to-settlement rate move
--                           lands once it is no longer a risk but a fact.
--   EQUITY:FX_ROUNDING      where sub-minor-unit remainders go. Rounding has to
--                           land somewhere explicit or it leaks.
--   CONTINGENT:FX_COMMITMENT / _CONTRA
--                           the off-balance-sheet pair holding open
--                           authorizations. FX_COMMITMENT is credit-normal because
--                           a commitment is an obligation: crediting it when an
--                           authorization opens makes its normal balance read as
--                           "buy currency we have promised and not yet delivered",
--                           which is the figure the verifier checks against the
--                           open authorizations.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN SELECT code FROM currency WHERE is_active ORDER BY code LOOP
        INSERT INTO account
            (code, name, currency_code, account_type, normal_side, is_contingent, allow_negative_balance)
        VALUES
            ('ASSET:NOSTRO:'                    || c.code, 'Nostro cash '          || c.code, c.code, 'ASSET',      'DEBIT',  FALSE, FALSE),
            ('ASSET:SUSPENSE:'                  || c.code, 'Suspense '             || c.code, c.code, 'ASSET',      'DEBIT',  FALSE, TRUE),
            ('LIABILITY:CUSTOMER_POOL:'         || c.code, 'Customer pool '        || c.code, c.code, 'LIABILITY',  'CREDIT', FALSE, TRUE),
            ('EQUITY:FX_POSITION:'              || c.code, 'FX position '          || c.code, c.code, 'EQUITY',     'CREDIT', FALSE, TRUE),
            ('EQUITY:FX_REALIZED_PNL:'          || c.code, 'Realized FX P&L '      || c.code, c.code, 'EQUITY',     'CREDIT', FALSE, TRUE),
            ('EQUITY:FX_ROUNDING:'              || c.code, 'FX rounding residual ' || c.code, c.code, 'EQUITY',     'CREDIT', FALSE, TRUE),
            ('CONTINGENT:FX_COMMITMENT:'        || c.code, 'FX commitment '        || c.code, c.code, 'CONTINGENT', 'CREDIT', TRUE,  TRUE),
            ('CONTINGENT:FX_COMMITMENT_CONTRA:' || c.code, 'FX commitment contra ' || c.code, c.code, 'CONTINGENT', 'DEBIT',  TRUE,  TRUE);
    END LOOP;
END $$;
