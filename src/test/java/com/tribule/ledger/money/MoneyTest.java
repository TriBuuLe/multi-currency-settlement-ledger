package com.tribule.ledger.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final CurrencyUnit USD = new CurrencyUnit("USD", 2);
    private static final CurrencyUnit JPY = new CurrencyUnit("JPY", 0);
    private static final CurrencyUnit KWD = new CurrencyUnit("KWD", 3);

    @Test
    @DisplayName("minor units are interpreted per currency, not as cents everywhere")
    void scaleIsPerCurrency() {
        assertThat(Money.ofMinor(1000, USD).toMajor()).isEqualByComparingTo("10.00");
        assertThat(Money.ofMinor(1000, JPY).toMajor()).isEqualByComparingTo("1000");
        assertThat(Money.ofMinor(1000, KWD).toMajor()).isEqualByComparingTo("1.000");
    }

    @Test
    @DisplayName("parsing a major amount with too much precision is an error, not a silent rounding")
    void refusesToRoundSilently() {
        assertThat(Money.ofMajor("10.00", USD).minorUnits()).isEqualTo(1000);
        assertThat(Money.ofMajor("1.005", KWD).minorUnits()).isEqualTo(1005);

        assertThatThrownBy(() -> Money.ofMajor("1.005", USD))
                .isInstanceOf(MoneyException.class)
                .hasMessageContaining("not representable");
        assertThatThrownBy(() -> Money.ofMajor("10.5", JPY))
                .isInstanceOf(MoneyException.class);
    }

    @Test
    @DisplayName("currencies cannot be mixed by arithmetic")
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.ofMinor(100, USD).plus(Money.ofMinor(100, JPY)))
                .isInstanceOf(MoneyException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("JPY");
    }

    @Test
    @DisplayName("overflow fails loudly instead of wrapping into a negative balance")
    void overflowThrows() {
        Money huge = Money.ofMinor(Long.MAX_VALUE, USD);
        assertThatThrownBy(() -> huge.plus(Money.ofMinor(1, USD)))
                .isInstanceOf(MoneyException.class)
                .hasMessageContaining("overflow");
    }

    @Test
    void arithmeticIsExact() {
        Money a = Money.ofMajor("0.07", USD);
        Money total = Money.zero(USD);
        for (int i = 0; i < 100; i++) {
            total = total.plus(a);
        }
        // The sum a float would get wrong.
        assertThat(total.minorUnits()).isEqualTo(700);
        assertThat(total.toMajor()).isEqualByComparingTo(new BigDecimal("7.00"));
    }

    @Test
    void signAndFormatting() {
        assertThat(Money.ofMinor(-250, USD).isNegative()).isTrue();
        assertThat(Money.ofMinor(-250, USD).abs().minorUnits()).isEqualTo(250);
        assertThat(Money.ofMinor(-250, USD)).hasToString("-2.50 USD");
        assertThat(Money.ofMinor(1234, KWD)).hasToString("1.234 KWD");
    }

    @Test
    void rejectsNonsenseCurrencies() {
        assertThatThrownBy(() -> new CurrencyUnit("US", 2)).isInstanceOf(MoneyException.class);
        assertThatThrownBy(() -> new CurrencyUnit("USD", 9)).isInstanceOf(MoneyException.class);
    }
}
