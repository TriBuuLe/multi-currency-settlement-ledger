package com.tribule.ledger.fx;

import com.tribule.ledger.money.CurrencyUnit;
import com.tribule.ledger.money.Money;
import com.tribule.ledger.support.AbstractLedgerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two independent time axes, and why one is not enough.
 *
 * <p>{@code effectiveAt} is when a price held in the market. {@code observedAt} is
 * when we found out about it. A table with only one timestamp cannot answer "what did
 * we believe the rate was last Tuesday, using only what we knew then?", and that is
 * the question every restatement, audit, and reproducible report turns out to need.
 */
class BitemporalRateTest extends AbstractLedgerTest {

    private static final CurrencyUnit USD = new CurrencyUnit("USD", 2);
    private static final CurrencyUnit EUR = new CurrencyUnit("EUR", 2);
    private static final CurrencyUnit KWD = new CurrencyUnit("KWD", 3);

    @Test
    @DisplayName("resolution takes the price in effect at the asked-for instant, not the newest one")
    void resolvesByValidTime() {
        Instant t0 = nextTimeline();
        Instant t1 = t0.plus(Duration.ofDays(1));
        Instant t2 = t0.plus(Duration.ofDays(2));
        publishRate("EUR", "USD", "1.10", t0);
        publishRate("EUR", "USD", "1.20", t1);
        publishRate("EUR", "USD", "1.30", t2);

        assertThat(fx.resolve("EUR", "USD", t0.plusSeconds(1), t2.plusSeconds(1)).rate())
                .isEqualByComparingTo("1.10");
        assertThat(fx.resolve("EUR", "USD", t1.plusSeconds(1), t2.plusSeconds(1)).rate())
                .isEqualByComparingTo("1.20");
        assertThat(fx.resolve("EUR", "USD", t2.plusSeconds(1), t2.plusSeconds(1)).rate())
                .isEqualByComparingTo("1.30");
    }

    @Test
    @DisplayName("a correction published later does not change what we knew at the time")
    void correctionDoesNotRewriteHistory() {
        Instant t0 = nextTimeline();
        Instant correctedAt = t0.plus(Duration.ofDays(3));
        FxRate original = fx.publish("EUR", "USD", new BigDecimal("1.10"), t0, t0, "vendor");

        // The vendor admits the tick was wrong and republishes it three days later.
        FxRate correction = fx.publishCorrection(original.id(), new BigDecimal("1.14"), correctedAt, "vendor-fix");

        assertThat(correction.effectiveAt())
                .as("a correction describes the same moment in the market")
                .isEqualTo(original.effectiveAt());
        assertThat(correction.observedAt()).isAfter(original.observedAt());
        assertThat(correction.supersedesId()).isEqualTo(original.id());

        // Asked as of the original knowledge time, the answer is still the wrong tick --
        // which is correct, because that is what we acted on.
        assertThat(fx.resolve("EUR", "USD", t0.plusSeconds(1), t0.plusSeconds(1)).rate())
                .isEqualByComparingTo("1.10");

        // Asked with today's knowledge, the answer is the corrected one.
        assertThat(fx.resolve("EUR", "USD", t0.plusSeconds(1), correctedAt.plusSeconds(1)).rate())
                .isEqualByComparingTo("1.14");

        // And the wrong row is still there, because nothing is ever edited.
        assertThat(fx.findById(original.id())).isPresent();
        assertThat(fx.correctionsOf(original.id())).extracting(FxRate::id).contains(correction.id());
    }

    @Test
    @DisplayName("an unquoted direction is resolved as the reciprocal of the quoted one")
    void resolvesInverse() {
        Instant t0 = nextTimeline();
        publishRate("EUR", "USD", "1.25", t0);

        ResolvedRate inverse = fx.resolve("USD", "EUR", t0.plusSeconds(1), t0.plusSeconds(1));

        assertThat(inverse.resolution()).isEqualTo(RateResolution.INVERSE);
        assertThat(inverse.rate().doubleValue()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(fx.convert(Money.ofMinor(10_000, USD), EUR, inverse).booked().minorUnits()).isEqualTo(8_000);
    }

    @Test
    @DisplayName("a pair quoted in neither direction is composed through the pivot currency")
    void resolvesTriangulated() {
        Instant t0 = nextTimeline();
        // JPY/KWD is not a quoted pair anywhere; both legs against USD are.
        publishRate("JPY", "USD", "0.0063572", t0);
        publishRate("USD", "KWD", "0.30712", t0);

        ResolvedRate triangulated = fx.resolve("JPY", "KWD", t0.plusSeconds(1), t0.plusSeconds(1));

        assertThat(triangulated.resolution()).isEqualTo(RateResolution.TRIANGULATED);
        assertThat(triangulated.pivotCurrency()).isEqualTo("USD");
        assertThat(triangulated.firstLegRate()).isEqualByComparingTo("0.0063572");
        assertThat(triangulated.secondLegRate()).isEqualByComparingTo("0.30712");
        assertThat(triangulated.rate())
                .isEqualByComparingTo(new BigDecimal("0.0063572").multiply(new BigDecimal("0.30712")));
        assertThat(triangulated.legRateIds()).hasSize(2);
    }

    @Test
    @DisplayName("a triangulated conversion reports the residual the pivot rounding creates")
    void triangulatedConversionReportsResidual() {
        Instant t0 = nextTimeline();
        publishRate("JPY", "USD", "0.0063572", t0);
        publishRate("USD", "KWD", "0.30712", t0);
        ResolvedRate rate = fx.resolve("JPY", "KWD", t0.plusSeconds(1), t0.plusSeconds(1));

        // Scan rather than guess: the identity must hold everywhere, and the residual
        // must be non-zero somewhere, or the rounding account would never be used.
        boolean sawResidual = false;
        for (long yen = 1; yen <= 1_000; yen++) {
            var conversion = fx.convert(Money.ofMinor(yen, new CurrencyUnit("JPY", 0)), KWD, rate);
            assertThat(conversion.booked().minorUnits() + conversion.residual().minorUnits())
                    .isEqualTo(conversion.market().minorUnits());
            sawResidual |= conversion.hasResidual();
        }
        assertThat(sawResidual).isTrue();
    }

    @Test
    @DisplayName("identity conversion needs no rate at all")
    void identityNeedsNoRate() {
        ResolvedRate identity = fx.resolve("USD", "USD", Instant.now(), Instant.now());

        assertThat(identity.resolution()).isEqualTo(RateResolution.IDENTITY);
        assertThat(identity.rate()).isEqualByComparingTo("1");
        assertThat(identity.primaryRateId()).isNull();
    }

    @Test
    @DisplayName("a pair with no usable rate fails loudly rather than guessing")
    void missingRateThrows() {
        // Before any rate in the database was effective, so direct, inverse, and
        // triangulated resolution all come up empty.
        Instant beforeEverything = Instant.parse("1999-01-01T00:00:00Z");

        assertThatThrownBy(() -> fx.resolve("EUR", "USD", beforeEverything, beforeEverything))
                .isInstanceOf(FxException.RateNotAvailable.class)
                .hasMessageContaining("EUR/USD");
    }

    @Test
    @DisplayName("history keeps only the latest observation of each instant")
    void historyExcludesSupersededObservations() {
        Instant t0 = nextTimeline();
        Instant t1 = t0.plus(Duration.ofDays(1));
        FxRate wrong = fx.publish("GBP", "CHF", new BigDecimal("1.11"), t0, t0, "vendor");
        fx.publish("GBP", "CHF", new BigDecimal("1.12"), t1, t1, "vendor");
        fx.publishCorrection(wrong.id(), new BigDecimal("1.19"), t1.plus(Duration.ofHours(1)), "vendor-fix");

        List<FxRate> series = fx.history("GBP", "CHF", t0.minusSeconds(1), t1.plusSeconds(1),
                t1.plus(Duration.ofDays(1)));

        assertThat(series).hasSize(2);
        assertThat(series.getFirst().rate())
                .as("the corrected value replaces the wrong one in the series, not sits beside it")
                .isEqualByComparingTo("1.19");
        assertThat(series.getLast().rate()).isEqualByComparingTo("1.12");
    }

    @Test
    @DisplayName("a correction must be observed after the rate it supersedes")
    void correctionCannotPredateItsOriginal() {
        Instant t0 = nextTimeline();
        FxRate original = fx.publish("EUR", "GBP", new BigDecimal("0.85"), t0, t0, "vendor");

        assertThatThrownBy(() -> fx.publishCorrection(
                original.id(), new BigDecimal("0.86"), t0.minusSeconds(1), "backwards"))
                .isInstanceOf(FxException.class)
                .hasMessageContaining("observed after");
    }

    @Test
    @DisplayName("published rates are immutable")
    void ratesAreAppendOnly() {
        Instant t0 = nextTimeline();
        FxRate rate = fx.publish("EUR", "SGD", new BigDecimal("1.45"), t0, t0, "vendor");

        assertThatThrownBy(() -> jdbc.sql("UPDATE fx_rate SET rate = 9.99 WHERE id = ?")
                .param(rate.id()).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");
    }
}
