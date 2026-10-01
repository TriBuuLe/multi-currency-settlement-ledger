package com.tribule.ledger.fx;

import com.tribule.ledger.config.LedgerProperties;
import com.tribule.ledger.money.Conversion;
import com.tribule.ledger.money.CurrencyUnit;
import com.tribule.ledger.money.FxMath;
import com.tribule.ledger.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Publishes rates and resolves them for a pair.
 *
 * <p>Resolution tries four things in order: identity, the quoted pair, the
 * reciprocal of the opposite pair, and finally a composition through a pivot
 * currency. Which one succeeded is carried in the result, because a triangulated
 * conversion has to round at the pivot and therefore behaves differently from a
 * direct one.
 */
@Service
public class FxRateService {

    private final FxRateRepository rates;
    private final LedgerProperties properties;

    public FxRateService(FxRateRepository rates, LedgerProperties properties) {
        this.rates = rates;
        this.properties = properties;
    }

    @Transactional
    public FxRate publish(String base, String quote, BigDecimal rate,
                          Instant effectiveAt, Instant observedAt, String source) {
        return rates.insert(base.toUpperCase(), quote.toUpperCase(), rate, effectiveAt,
                observedAt == null ? Instant.now() : observedAt,
                source == null ? "manual" : source, null);
    }

    /**
     * Publishes a corrected price for an instant we already have an observation
     * for.
     *
     * <p>The wrong row is left exactly where it is. The correction gets the same
     * {@code effectiveAt} and a later {@code observedAt}, so "what did we think
     * at the time" and "what do we now know" stay separately answerable -- which
     * is the whole reason to pay the cost of two time axes.
     */
    @Transactional
    public FxRate publishCorrection(UUID supersededRateId, BigDecimal correctedRate,
                                    Instant observedAt, String source) {
        FxRate superseded = rates.findById(supersededRateId)
                .orElseThrow(() -> new FxException.UnknownRate(supersededRateId));
        Instant observed = observedAt == null ? Instant.now() : observedAt;
        if (!observed.isAfter(superseded.observedAt())) {
            throw new FxException("a correction must be observed after the rate it supersedes");
        }
        return rates.insert(superseded.baseCurrency(), superseded.quoteCurrency(), correctedRate,
                superseded.effectiveAt(), observed,
                source == null ? "correction" : source, supersededRateId);
    }

    /** The rate to use right now, as known right now. */
    public ResolvedRate resolveNow(String from, String to) {
        Instant now = Instant.now();
        return resolve(from, to, now, now);
    }

    public ResolvedRate resolve(String from, String to, Instant effectiveAt, Instant knownAt) {
        String base = from.toUpperCase();
        String quote = to.toUpperCase();

        if (base.equals(quote)) {
            return ResolvedRate.identity(base);
        }

        Optional<FxRate> direct = rates.findAsOf(base, quote, effectiveAt, knownAt);
        if (direct.isPresent()) {
            return ResolvedRate.direct(direct.get());
        }

        Optional<FxRate> opposite = rates.findAsOf(quote, base, effectiveAt, knownAt);
        if (opposite.isPresent()) {
            return ResolvedRate.inverse(opposite.get(), FxMath.invert(opposite.get().rate()));
        }

        return triangulate(base, quote, effectiveAt, knownAt);
    }

    /**
     * Composes a rate through the pivot currency, the way a desk does when a pair
     * is not quoted (JPY/KWD, say).
     */
    private ResolvedRate triangulate(String base, String quote, Instant effectiveAt, Instant knownAt) {
        String pivot = properties.pivotCurrency();
        if (base.equals(pivot) || quote.equals(pivot)) {
            throw new FxException.RateNotAvailable(base, quote, effectiveAt, knownAt);
        }

        Optional<LegRate> first = leg(base, pivot, effectiveAt, knownAt);
        Optional<LegRate> second = leg(pivot, quote, effectiveAt, knownAt);
        if (first.isEmpty() || second.isEmpty()) {
            throw new FxException.RateNotAvailable(base, quote, effectiveAt, knownAt);
        }

        BigDecimal composite = FxMath.crossRate(first.get().rate(), second.get().rate());
        List<UUID> legIds = new ArrayList<>(List.of(first.get().sourceId(), second.get().sourceId()));
        return ResolvedRate.triangulated(base, quote, pivot,
                first.get().rate(), second.get().rate(), composite, first.get().sourceId(), legIds);
    }

    private Optional<LegRate> leg(String base, String quote, Instant effectiveAt, Instant knownAt) {
        Optional<FxRate> direct = rates.findAsOf(base, quote, effectiveAt, knownAt);
        if (direct.isPresent()) {
            return Optional.of(new LegRate(direct.get().rate(), direct.get().id()));
        }
        return rates.findAsOf(quote, base, effectiveAt, knownAt)
                .map(r -> new LegRate(FxMath.invert(r.rate()), r.id()));
    }

    /**
     * Converts an amount using a resolved rate.
     *
     * <p>A triangulated conversion is computed leg by leg, exactly as it will be
     * booked, and the gap between that and the full-precision cross rate is
     * returned as a residual for the caller to post to the rounding account. A
     * direct conversion has no such gap.
     */
    public Conversion convert(Money source, CurrencyUnit target, ResolvedRate resolved) {
        if (resolved.isTriangulated()) {
            CurrencyUnit pivot = properties.pivotCurrencyUnit();
            return FxMath.convertViaPivot(source, pivot, resolved.firstLegRate(),
                    target, resolved.secondLegRate(), FxMath.DEFAULT_ROUNDING);
        }
        return FxMath.convertDirect(source, target, resolved.rate(), FxMath.DEFAULT_ROUNDING);
    }

    public Optional<FxRate> findById(UUID id) {
        return rates.findById(id);
    }

    public List<FxRate> history(String base, String quote, Instant from, Instant to, Instant knownAt) {
        return rates.findHistory(base.toUpperCase(), quote.toUpperCase(), from, to, knownAt);
    }

    public List<FxRate> correctionsOf(UUID rateId) {
        return rates.findCorrectionsOf(rateId);
    }

    private record LegRate(BigDecimal rate, UUID sourceId) {
    }
}
