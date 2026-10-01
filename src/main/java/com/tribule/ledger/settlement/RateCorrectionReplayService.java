package com.tribule.ledger.settlement;

import com.tribule.ledger.fx.FxException;
import com.tribule.ledger.fx.FxRate;
import com.tribule.ledger.fx.FxRateService;
import com.tribule.ledger.fx.ResolvedRate;
import com.tribule.ledger.ledger.ChartOfAccounts;
import com.tribule.ledger.ledger.CurrencyRepository;
import com.tribule.ledger.ledger.JournalEntry;
import com.tribule.ledger.ledger.LedgerService;
import com.tribule.ledger.ledger.PostedTransaction;
import com.tribule.ledger.ledger.PostingCommand;
import com.tribule.ledger.ledger.TransactionKind;
import com.tribule.ledger.money.Conversion;
import com.tribule.ledger.money.CurrencyUnit;
import com.tribule.ledger.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Restates settlements that were priced off a rate the vendor later corrected.
 *
 * <p>This is the payoff for storing rates bitemporally, and it is worth being
 * precise about why. Replaying a settlement does not need special correction logic
 * at all -- it re-runs the ordinary settlement arithmetic with one input changed:
 *
 * <pre>
 *   originally:  resolve(pair, effectiveAt = settledAt, knownAt = settledAt)
 *   on replay:   resolve(pair, effectiveAt = settledAt, knownAt = now)
 * </pre>
 *
 * <p>Same function, same pair, same valid time. Only the knowledge time moves, and
 * the corrected observation comes back instead of the wrong one. A ledger that
 * stored a single timestamp per rate could not express this question, which is why
 * systems without it end up correcting history by hand.
 *
 * <h2>What gets adjusted, and what does not</h2>
 *
 * <p>The customer is not touched. They were quoted a rate, they were paid that
 * rate, and a vendor's bad tick is not their problem -- so the wallet leg of the
 * original settlement stands. What was wrong is the house's own accounting of how
 * much the market actually gave it, so the adjustment moves only the house's three
 * accounts: FX position, realized P&amp;L, and rounding.
 *
 * <p>Nothing is edited. The original settlement stays in the journal exactly as it
 * was booked, with a separate adjusting transaction beside it, so "what we thought
 * at the time" and "what we now know" both remain answerable.
 */
@Service
public class RateCorrectionReplayService {

    /** One restated settlement. */
    public record Adjustment(
            UUID authorizationId,
            String reference,
            long originalPnlMinor,
            long correctedPnlMinor,
            long pnlDeltaMinor,
            String currency,
            UUID adjustmentTransactionId) {
    }

    public record ReplayResult(
            UUID correctionRateId,
            UUID supersededRateId,
            String pair,
            Instant effectiveAt,
            int settlementsExamined,
            int adjustmentsPosted,
            long netPnlDeltaMinor,
            List<Adjustment> adjustments,
            List<String> notes) {

        public ReplayResult {
            adjustments = List.copyOf(adjustments);
            notes = List.copyOf(notes);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(RateCorrectionReplayService.class);

    private final AuthorizationRepository authorizations;
    private final FxRateService fx;
    private final CurrencyRepository currencies;
    private final LedgerService ledger;

    public RateCorrectionReplayService(AuthorizationRepository authorizations,
                                      FxRateService fx,
                                      CurrencyRepository currencies,
                                      LedgerService ledger) {
        this.authorizations = authorizations;
        this.fx = fx;
        this.currencies = currencies;
        this.ledger = ledger;
    }

    @Transactional
    public ReplayResult replay(UUID correctionRateId, Instant knownAt) {
        FxRate correction = fx.findById(correctionRateId)
                .orElseThrow(() -> new FxException.UnknownRate(correctionRateId));
        if (!correction.isCorrection()) {
            throw new FxException("rate %s is not a correction: it supersedes nothing".formatted(correctionRateId));
        }
        Instant knowledgeTime = knownAt == null ? Instant.now() : knownAt;

        List<FxAuthorization> affected =
                authorizations.findSettledPricedOn(List.of(correction.supersedesId()));
        List<Adjustment> adjustments = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        long netDelta = 0;

        for (FxAuthorization authorization : affected) {
            CurrencyUnit sell = currencies.require(authorization.sellCurrency());
            CurrencyUnit buy = currencies.require(authorization.buyCurrency());

            // The original figures come from the journal, not from the
            // authorization row: the journal is what was actually booked.
            Booked original = readBooked(authorization, buy.code());

            // Re-run the same resolution with a later knowledge time.
            ResolvedRate corrected = fx.resolve(
                    sell.code(), buy.code(), authorization.settledAt(), knowledgeTime);
            Conversion restated = fx.convert(
                    Money.ofMinor(authorization.sellAmountMinor(), sell), buy, corrected);

            long marketDelta = restated.market().minorUnits() - original.marketMinor();
            long bookedDelta = restated.booked().minorUnits() - original.bookedMinor();
            long residualDelta = restated.residual().minorUnits() - original.residualMinor();

            if (marketDelta == 0 && bookedDelta == 0 && residualDelta == 0) {
                notes.add("%s needed no adjustment: the correction does not change its booked amounts"
                        .formatted(authorization.reference()));
                continue;
            }

            PostedTransaction adjustment = ledger.post(
                    PostingCommand.of(TransactionKind.RATE_CORRECTION_ADJUSTMENT, knowledgeTime)
                            .reference(authorization.reference())
                            .description("restated %s for corrected %s rate effective %s"
                                    .formatted(authorization.reference(), correction.pair(), correction.effectiveAt()))
                            .fxRate(correction.id())
                            .correlationId(authorization.reference())
                            // What the market actually gave us, restated.
                            .signed(ChartOfAccounts.fxPosition(buy.code()), marketDelta,
                                    "market value restated by " + marketDelta)
                            // The trading result that follows from it.
                            .signed(ChartOfAccounts.fxRealizedPnl(buy.code()), -bookedDelta,
                                    "realized P&L restated by " + bookedDelta)
                            // And the triangulation drift that follows from both.
                            .signed(ChartOfAccounts.fxRounding(buy.code()), -residualDelta,
                                    "rounding restated by " + residualDelta)
                            .build());

            long originalPnl = original.bookedMinor() - authorization.quotedBuyAmountMinor();
            long correctedPnl = restated.booked().minorUnits() - authorization.quotedBuyAmountMinor();
            netDelta += bookedDelta;
            adjustments.add(new Adjustment(authorization.id(), authorization.reference(),
                    originalPnl, correctedPnl, bookedDelta, buy.code(), adjustment.transaction().id()));
        }

        if (affected.isEmpty()) {
            notes.add("no settled authorization was priced off the superseded rate");
        }
        notes.add("customer balances are untouched: the quoted rate was honoured and a vendor correction "
                + "does not change what the customer was owed");

        log.info("replayed correction {} over {} settlement(s), {} adjustment(s), net P&L delta {}",
                correctionRateId, affected.size(), adjustments.size(), netDelta);

        return new ReplayResult(correction.id(), correction.supersedesId(), correction.pair(),
                correction.effectiveAt(), affected.size(), adjustments.size(), netDelta, adjustments, notes);
    }

    /** What the original settlement actually put on the house's buy-side accounts. */
    private Booked readBooked(FxAuthorization authorization, String buyCurrency) {
        List<JournalEntry> entries = ledger.require(authorization.settlementTransactionId()).entries();
        long market = signedOn(entries, ChartOfAccounts.fxPosition(buyCurrency));
        long residual = -signedOn(entries, ChartOfAccounts.fxRounding(buyCurrency));
        long booked = market - residual;
        return new Booked(market, booked, residual);
    }

    private long signedOn(List<JournalEntry> entries, String accountCode) {
        long total = 0;
        for (JournalEntry entry : entries) {
            if (accountCode.equals(entry.accountCode())) {
                total += entry.signedAmountMinor();
            }
        }
        return total;
    }

    private record Booked(long marketMinor, long bookedMinor, long residualMinor) {
    }
}
