package com.tribule.ledger.risk;

/**
 * The house's net position in one currency from unsettled authorizations.
 *
 * <p>Positive is long: we are owed, or already hold, more of this currency than we
 * owe. Negative is short -- we have promised currency we do not yet have, which is
 * the side that hurts when the rate moves against us.
 */
public record CurrencyExposure(
        String currency,
        long netPositionMinor,
        long netPositionReportingMinor,
        int authorizationCount) {

    public boolean isShort() {
        return netPositionMinor < 0;
    }
}
