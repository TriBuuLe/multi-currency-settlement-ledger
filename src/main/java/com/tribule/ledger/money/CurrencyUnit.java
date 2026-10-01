package com.tribule.ledger.money;

import java.math.BigDecimal;

/**
 * A currency and the only thing the ledger needs to know about it: how many
 * minor units make one major unit.
 *
 * <p>Hard-coding two decimal places is the most common money bug in financial
 * software. JPY has none, KWD has three, and a system that assumes cents will
 * quietly misprice both by a factor of 100 or 1000.
 */
public record CurrencyUnit(String code, int minorUnitScale) {

    public CurrencyUnit {
        if (code == null || code.length() != 3) {
            throw new MoneyException("currency code must be 3 characters, got: " + code);
        }
        if (minorUnitScale < 0 || minorUnitScale > 4) {
            throw new MoneyException("unsupported minor unit scale for " + code + ": " + minorUnitScale);
        }
        code = code.toUpperCase();
    }

    /** 10^minorUnitScale: the number of minor units in one major unit. */
    public BigDecimal scaleFactor() {
        return BigDecimal.ONE.movePointRight(minorUnitScale);
    }

    @Override
    public String toString() {
        return code;
    }
}
