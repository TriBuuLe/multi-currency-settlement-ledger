package com.tribule.ledger.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An exact amount of one currency, held as a signed count of minor units.
 *
 * <p>There is no {@code double} anywhere in this type. Arithmetic uses
 * {@link Math#addExact} so that an overflow is a loud failure rather than a
 * silent wrap into a negative balance, and every operation refuses to mix
 * currencies.
 */
public record Money(long minorUnits, CurrencyUnit currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money ofMinor(long minorUnits, CurrencyUnit currency) {
        return new Money(minorUnits, currency);
    }

    public static Money zero(CurrencyUnit currency) {
        return new Money(0L, currency);
    }

    /**
     * Parses a major-unit amount, refusing anything that would lose precision.
     * {@code "1.005"} in USD is an error, not 1.00 or 1.01 -- if a caller wants
     * rounding it has to ask for it explicitly.
     */
    public static Money ofMajor(BigDecimal major, CurrencyUnit currency) {
        try {
            long minor = major.setScale(currency.minorUnitScale(), RoundingMode.UNNECESSARY)
                    .movePointRight(currency.minorUnitScale())
                    .longValueExact();
            return new Money(minor, currency);
        } catch (ArithmeticException e) {
            throw new MoneyException(
                    "%s is not representable in %s (scale %d)".formatted(major, currency.code(), currency.minorUnitScale()));
        }
    }

    public static Money ofMajor(String major, CurrencyUnit currency) {
        return ofMajor(new BigDecimal(major), currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(addExact(minorUnits, negateExact(other.minorUnits)), currency);
    }

    public Money negated() {
        return new Money(negateExact(minorUnits), currency);
    }

    public Money abs() {
        return minorUnits < 0 ? negated() : this;
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minorUnits).movePointLeft(currency.minorUnitScale());
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    @Override
    public String toString() {
        return toMajor().toPlainString() + " " + currency.code();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.code().equals(other.currency.code())) {
            throw new MoneyException(
                    "cannot combine %s and %s".formatted(currency.code(), other.currency.code()));
        }
    }

    private static long addExact(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new MoneyException("amount overflow: " + a + " + " + b);
        }
    }

    private static long negateExact(long a) {
        try {
            return Math.negateExact(a);
        } catch (ArithmeticException e) {
            throw new MoneyException("amount overflow negating " + a);
        }
    }
}
