package com.tribule.ledger.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Currency conversion and exact splitting.
 *
 * <p>Everything here is {@link BigDecimal} in, {@code long} minor units out,
 * with the rounding mode always supplied by the caller. HALF_EVEN is the
 * default the services use: HALF_UP is biased upward and, applied to millions
 * of conversions, that bias is a real and auditable loss.
 */
public final class FxMath {

    public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_EVEN;

    private FxMath() {
    }

    /** Converts at a single rate. {@code rate} is target units per source unit. */
    public static Money convert(Money source, CurrencyUnit target, BigDecimal rate, RoundingMode mode) {
        requirePositiveRate(rate);
        BigDecimal targetMinor = BigDecimal.valueOf(source.minorUnits())
                .movePointLeft(source.currency().minorUnitScale())
                .multiply(rate)
                .movePointRight(target.minorUnitScale());
        try {
            return Money.ofMinor(targetMinor.setScale(0, mode).longValueExact(), target);
        } catch (ArithmeticException e) {
            throw new MoneyException("converted amount overflows a 64-bit minor unit count: " + targetMinor);
        }
    }

    public static Money convert(Money source, CurrencyUnit target, BigDecimal rate) {
        return convert(source, target, rate, DEFAULT_ROUNDING);
    }

    /**
     * Converts through a pivot currency, the way a desk does when no direct pair
     * is quoted (JPY/KWD via USD, say).
     *
     * <p>The pivot leg has to be rounded to a bookable amount before the second
     * leg is applied, and that rounding makes the two-leg result differ from the
     * exact cross rate. With a scale-0 source and a scale-3 target the gap is
     * routinely a whole minor unit -- so the difference is returned as a
     * residual for the caller to post, not swallowed.
     */
    public static Conversion convertViaPivot(Money source,
                                             CurrencyUnit pivot,
                                             BigDecimal sourceToPivot,
                                             CurrencyUnit target,
                                             BigDecimal pivotToTarget,
                                             RoundingMode mode) {
        Money pivotLeg = convert(source, pivot, sourceToPivot, mode);
        Money booked = convert(pivotLeg, target, pivotToTarget, mode);

        BigDecimal crossRate = sourceToPivot.multiply(pivotToTarget);
        Money market = convert(source, target, crossRate, mode);

        return new Conversion(booked, market, market.minus(booked), crossRate);
    }

    /** A direct conversion, expressed as a {@link Conversion} with no residual. */
    public static Conversion convertDirect(Money source, CurrencyUnit target, BigDecimal rate, RoundingMode mode) {
        Money booked = convert(source, target, rate, mode);
        return new Conversion(booked, booked, Money.zero(target), rate);
    }

    /**
     * Splits an amount by weight so that the parts sum to exactly the total.
     *
     * <p>Rounding each share independently loses or invents minor units; the
     * largest-remainder method hands the leftovers to the shares with the
     * biggest fractional parts, which is both fair and exact. Computed in
     * {@link BigInteger} because {@code total * weight} overflows a long long
     * before either factor does.
     */
    public static long[] allocate(long totalMinor, long[] weights) {
        if (weights.length == 0) {
            throw new MoneyException("cannot allocate across zero weights");
        }
        BigInteger weightSum = BigInteger.ZERO;
        for (long w : weights) {
            if (w < 0) {
                throw new MoneyException("allocation weights must be non-negative, got " + w);
            }
            weightSum = weightSum.add(BigInteger.valueOf(w));
        }
        if (weightSum.signum() == 0) {
            throw new MoneyException("allocation weights sum to zero");
        }

        BigInteger total = BigInteger.valueOf(totalMinor);
        long[] shares = new long[weights.length];
        BigInteger[] remainders = new BigInteger[weights.length];
        BigInteger allocated = BigInteger.ZERO;

        for (int i = 0; i < weights.length; i++) {
            BigInteger numerator = total.multiply(BigInteger.valueOf(weights[i]));
            BigInteger[] divMod = numerator.divideAndRemainder(weightSum);
            shares[i] = divMod[0].longValueExact();
            remainders[i] = divMod[1].abs();
            allocated = allocated.add(divMod[0]);
        }

        long leftover = total.subtract(allocated).longValueExact();
        long step = leftover < 0 ? -1 : 1;
        for (long n = 0; n < Math.abs(leftover); n++) {
            int best = -1;
            for (int i = 0; i < weights.length; i++) {
                if (weights[i] == 0) {
                    continue;
                }
                if (best == -1 || remainders[i].compareTo(remainders[best]) > 0) {
                    best = i;
                }
            }
            shares[best] += step;
            remainders[best] = BigInteger.valueOf(-1);
        }
        return shares;
    }

    /** The exact cross rate implied by two legs. */
    public static BigDecimal crossRate(BigDecimal sourceToPivot, BigDecimal pivotToTarget) {
        requirePositiveRate(sourceToPivot);
        requirePositiveRate(pivotToTarget);
        return sourceToPivot.multiply(pivotToTarget);
    }

    /** The inverse of a quoted rate, kept at high precision. */
    public static BigDecimal invert(BigDecimal rate) {
        requirePositiveRate(rate);
        return BigDecimal.ONE.divide(rate, 20, RoundingMode.HALF_EVEN);
    }

    private static void requirePositiveRate(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw new MoneyException("fx rate must be positive, got: " + rate);
        }
    }
}
