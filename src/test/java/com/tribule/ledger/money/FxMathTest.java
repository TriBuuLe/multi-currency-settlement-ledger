package com.tribule.ledger.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FxMathTest {

    private static final CurrencyUnit USD = new CurrencyUnit("USD", 2);
    private static final CurrencyUnit EUR = new CurrencyUnit("EUR", 2);
    private static final CurrencyUnit JPY = new CurrencyUnit("JPY", 0);
    private static final CurrencyUnit KWD = new CurrencyUnit("KWD", 3);

    @Test
    @DisplayName("conversion respects both currencies' scales")
    void convertsAcrossScales() {
        // 100.00 USD at 157.25 JPY/USD = 15725 JPY, scale 0.
        assertThat(FxMath.convert(Money.ofMinor(10_000, USD), JPY, new BigDecimal("157.25")).minorUnits())
                .isEqualTo(15_725);
        // 1000 JPY at 0.00635 USD/JPY = 6.35 USD.
        assertThat(FxMath.convert(Money.ofMinor(1000, JPY), USD, new BigDecimal("0.00635")).minorUnits())
                .isEqualTo(635);
    }

    @Test
    @DisplayName("HALF_EVEN is the default because HALF_UP is biased upward")
    void defaultRoundingIsUnbiased() {
        // 0.125 -> 0.12 under HALF_EVEN, 0.13 under HALF_UP.
        Money source = Money.ofMinor(1000, USD);
        assertThat(FxMath.convert(source, EUR, new BigDecimal("0.000125")).minorUnits()).isZero();

        Money halfCentUp = Money.ofMinor(5, USD);
        assertThat(FxMath.convert(halfCentUp, EUR, new BigDecimal("0.5"), RoundingMode.HALF_EVEN).minorUnits())
                .isEqualTo(2);
        assertThat(FxMath.convert(halfCentUp, EUR, new BigDecimal("0.5"), RoundingMode.HALF_UP).minorUnits())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a direct conversion has no residual to post")
    void directConversionHasNoResidual() {
        Conversion conversion = FxMath.convertDirect(
                Money.ofMinor(10_000, USD), EUR, new BigDecimal("0.92"), FxMath.DEFAULT_ROUNDING);
        assertThat(conversion.booked().minorUnits()).isEqualTo(9200);
        assertThat(conversion.hasResidual()).isFalse();
        assertThat(conversion.residual().isZero()).isTrue();
    }

    @Test
    @DisplayName("triangulating through a pivot creates real residuals, and they always reconcile")
    void pivotRoundingProducesAResidual() {
        // JPY -> USD -> KWD. The USD leg must be rounded to cents before the second
        // leg is applied, so the two-leg answer and the exact cross-rate answer do
        // not always agree. Rather than picking one magic amount -- plenty of them
        // happen to round identically both ways -- scan a range. The reconciliation
        // identity must hold for every amount, and the residual must be non-zero for
        // at least some of them, otherwise the rounding account would be pointless
        // and this code path would be dead weight.
        BigDecimal jpyUsd = new BigDecimal("0.0063572");
        BigDecimal usdKwd = new BigDecimal("0.30712");
        BigDecimal crossRate = FxMath.crossRate(jpyUsd, usdKwd);

        int amountsWithResidual = 0;
        long largestResidual = 0;
        for (long yen = 1; yen <= 5_000; yen++) {
            Money source = Money.ofMinor(yen, JPY);
            Conversion viaPivot = FxMath.convertViaPivot(
                    source, USD, jpyUsd, KWD, usdKwd, FxMath.DEFAULT_ROUNDING);

            // The invariant the settlement postings depend on: what the market gives
            // equals what we book plus what goes to the rounding account.
            assertThat(viaPivot.booked().minorUnits() + viaPivot.residual().minorUnits())
                    .as("booked + residual must equal market value for %d JPY", yen)
                    .isEqualTo(viaPivot.market().minorUnits());
            assertThat(viaPivot.market()).isEqualTo(FxMath.convert(source, KWD, crossRate));

            if (viaPivot.hasResidual()) {
                amountsWithResidual++;
                largestResidual = Math.max(largestResidual, Math.abs(viaPivot.residual().minorUnits()));
            }
        }

        assertThat(amountsWithResidual)
                .as("rounding at the pivot should make some amounts differ from the exact cross rate")
                .isPositive();
        assertThat(largestResidual)
                .as("the residual is small, but it is whole minor units of real money")
                .isPositive();
    }

    @Test
    @DisplayName("allocation parts always sum to the whole")
    void allocationIsExact() {
        long[] thirds = FxMath.allocate(100, new long[]{1, 1, 1});
        assertThat(thirds).containsExactly(34, 33, 33);
        assertThat(thirds[0] + thirds[1] + thirds[2]).isEqualTo(100);

        long[] weighted = FxMath.allocate(1_000_000_01L, new long[]{7, 11, 13, 1});
        assertThat(java.util.Arrays.stream(weighted).sum()).isEqualTo(1_000_000_01L);

        long[] withZero = FxMath.allocate(10, new long[]{0, 1, 0});
        assertThat(withZero).containsExactly(0, 10, 0);
    }

    @Test
    void rejectsImpossibleInputs() {
        assertThatThrownBy(() -> FxMath.convert(Money.ofMinor(1, USD), EUR, BigDecimal.ZERO))
                .isInstanceOf(MoneyException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> FxMath.allocate(10, new long[]{0, 0}))
                .isInstanceOf(MoneyException.class)
                .hasMessageContaining("sum to zero");
        assertThatThrownBy(() -> FxMath.allocate(10, new long[]{}))
                .isInstanceOf(MoneyException.class);
    }

    @Test
    void inversionRoundTripsWithinPrecision() {
        BigDecimal rate = new BigDecimal("157.2500");
        BigDecimal inverted = FxMath.invert(rate);
        assertThat(FxMath.invert(inverted).doubleValue()).isCloseTo(157.25, org.assertj.core.data.Offset.offset(1e-10));
    }
}
