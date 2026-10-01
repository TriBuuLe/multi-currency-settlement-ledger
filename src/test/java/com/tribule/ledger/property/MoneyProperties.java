package com.tribule.ledger.property;

import com.tribule.ledger.money.Conversion;
import com.tribule.ledger.money.CurrencyUnit;
import com.tribule.ledger.money.FxMath;
import com.tribule.ledger.money.Money;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties of the money arithmetic, checked against generated inputs.
 *
 * <p>Example-based tests only prove the examples somebody thought of. These state the
 * laws the arithmetic has to obey and let jqwik go looking for the counterexample --
 * which is the right shape of test for rounding and allocation, where the bugs live in
 * the inputs nobody would pick by hand.
 */
class MoneyProperties {

    @Property(tries = 500)
    void allocationAlwaysSumsToTheTotal(
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long totalMinor,
            @ForAll @Size(min = 1, max = 8) List<@LongRange(min = 0, max = 10_000) Long> weights) {
        long[] asArray = weights.stream().mapToLong(Long::longValue).toArray();
        Assume.that(java.util.Arrays.stream(asArray).sum() > 0);

        long[] shares = FxMath.allocate(totalMinor, asArray);

        assertThat(java.util.Arrays.stream(shares).sum())
                .as("splitting money must never create or destroy a minor unit")
                .isEqualTo(totalMinor);
        assertThat(shares).hasSameSizeAs(asArray);
    }

    @Property(tries = 500)
    void zeroWeightsReceiveNothing(
            @ForAll @LongRange(min = 0, max = 1_000_000) long totalMinor,
            @ForAll @Size(min = 2, max = 6) List<@LongRange(min = 1, max = 100) Long> weights) {
        long[] withZero = new long[weights.size() + 1];
        withZero[0] = 0;
        for (int i = 0; i < weights.size(); i++) {
            withZero[i + 1] = weights.get(i);
        }

        long[] shares = FxMath.allocate(totalMinor, withZero);

        assertThat(shares[0]).as("a zero weight is entitled to nothing").isZero();
        assertThat(java.util.Arrays.stream(shares).sum()).isEqualTo(totalMinor);
    }

    @Property(tries = 500)
    void additionAndSubtractionAreInverse(
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long a,
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long b,
            @ForAll @IntRange(min = 0, max = 4) int scale) {
        CurrencyUnit currency = new CurrencyUnit("USD", scale);
        Money first = Money.ofMinor(a, currency);
        Money second = Money.ofMinor(b, currency);

        assertThat(first.plus(second).minus(second)).isEqualTo(first);
        assertThat(first.plus(second)).isEqualTo(second.plus(first));
        assertThat(first.minus(first).isZero()).isTrue();
    }

    @Property(tries = 500)
    void majorAndMinorRoundTrip(
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long minorUnits,
            @ForAll @IntRange(min = 0, max = 4) int scale) {
        CurrencyUnit currency = new CurrencyUnit("EUR", scale);
        Money money = Money.ofMinor(minorUnits, currency);

        assertThat(Money.ofMajor(money.toMajor(), currency)).isEqualTo(money);
    }

    @Property(tries = 500)
    void conversionIsMonotonicInAmount(
            @ForAll @LongRange(min = 0, max = 1_000_000_000L) long smaller,
            @ForAll @LongRange(min = 0, max = 1_000_000_000L) long larger,
            @ForAll @LongRange(min = 1, max = 100_000_000L) long rateMicros) {
        Assume.that(smaller <= larger);
        CurrencyUnit from = new CurrencyUnit("USD", 2);
        CurrencyUnit to = new CurrencyUnit("JPY", 0);
        BigDecimal rate = BigDecimal.valueOf(rateMicros, 6);

        long convertedSmaller = FxMath.convert(Money.ofMinor(smaller, from), to, rate).minorUnits();
        long convertedLarger = FxMath.convert(Money.ofMinor(larger, from), to, rate).minorUnits();

        assertThat(convertedSmaller)
                .as("converting more money must never yield less")
                .isLessThanOrEqualTo(convertedLarger);
    }

    @Property(tries = 500)
    void triangulationAlwaysReconciles(
            @ForAll @LongRange(min = 1, max = 100_000_000L) long sourceMinor,
            @ForAll @LongRange(min = 1, max = 10_000_000L) long firstLegMicros,
            @ForAll @LongRange(min = 1, max = 10_000_000L) long secondLegMicros,
            @ForAll @IntRange(min = 0, max = 3) int targetScale) {
        CurrencyUnit source = new CurrencyUnit("JPY", 0);
        CurrencyUnit pivot = new CurrencyUnit("USD", 2);
        CurrencyUnit target = new CurrencyUnit("KWD", targetScale);
        BigDecimal firstLeg = BigDecimal.valueOf(firstLegMicros, 7);
        BigDecimal secondLeg = BigDecimal.valueOf(secondLegMicros, 7);

        Conversion conversion = FxMath.convertViaPivot(
                Money.ofMinor(sourceMinor, source), pivot, firstLeg, target, secondLeg,
                FxMath.DEFAULT_ROUNDING);

        // The identity every settlement posting depends on. If this can ever fail, the
        // buy side of a settlement does not balance and the database rejects the write.
        assertThat(conversion.booked().minorUnits() + conversion.residual().minorUnits())
                .as("booked + residual must equal the market value")
                .isEqualTo(conversion.market().minorUnits());
        assertThat(conversion.booked().currency().code()).isEqualTo(target.code());
        assertThat(conversion.residual().currency().code()).isEqualTo(target.code());
    }

    @Property(tries = 300)
    void crossRateMatchesSequentialConversionWithinRounding(
            @ForAll @LongRange(min = 1_000, max = 100_000_000L) long sourceMinor,
            @ForAll @LongRange(min = 100_000, max = 10_000_000L) long firstLegMicros,
            @ForAll @LongRange(min = 100_000, max = 10_000_000L) long secondLegMicros) {
        CurrencyUnit source = new CurrencyUnit("USD", 2);
        CurrencyUnit pivot = new CurrencyUnit("EUR", 2);
        CurrencyUnit target = new CurrencyUnit("GBP", 2);
        BigDecimal firstLeg = BigDecimal.valueOf(firstLegMicros, 6);
        BigDecimal secondLeg = BigDecimal.valueOf(secondLegMicros, 6);

        Conversion conversion = FxMath.convertViaPivot(
                Money.ofMinor(sourceMinor, source), pivot, firstLeg, target, secondLeg,
                FxMath.DEFAULT_ROUNDING);

        // Triangulating is not the same as applying the cross rate -- that is the whole
        // reason the residual exists -- but the gap is bounded by the rounding of one
        // intermediate leg, not unbounded.
        BigDecimal bound = secondLeg.multiply(BigDecimal.valueOf(100))
                .add(BigDecimal.ONE)
                .setScale(0, java.math.RoundingMode.CEILING);
        assertThat(Math.abs(conversion.residual().minorUnits()))
                .isLessThanOrEqualTo(bound.longValueExact());
    }
}
