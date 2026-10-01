package com.tribule.ledger.money;

import java.math.BigDecimal;

/**
 * The result of converting money across currencies.
 *
 * @param booked    what the ledger actually moves, in target minor units
 * @param market    what the full-precision cross rate says it is worth, in
 *                  target minor units
 * @param residual  {@code market - booked}: a whole number of minor units that
 *                  exists because an intermediate leg had to be rounded to a
 *                  bookable amount. It is small but it is real money, so it is
 *                  posted to an explicit rounding account rather than dropped
 * @param crossRate the effective end-to-end rate, exact
 */
public record Conversion(Money booked, Money market, Money residual, BigDecimal crossRate) {

    public boolean hasResidual() {
        return !residual.isZero();
    }
}
