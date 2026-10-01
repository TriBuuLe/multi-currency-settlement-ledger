package com.tribule.ledger.web;

import com.tribule.ledger.fx.FxRate;
import com.tribule.ledger.fx.FxRateService;
import com.tribule.ledger.settlement.RateCorrectionReplayService;
import com.tribule.ledger.web.dto.Requests;
import com.tribule.ledger.web.dto.Responses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fx")
@Tag(name = "FX rates", description = "Bitemporal rate publication, resolution, correction, and replay")
public class FxController {

    private final FxRateService fx;
    private final RateCorrectionReplayService replay;
    private final ViewMapper views;

    public FxController(FxRateService fx, RateCorrectionReplayService replay, ViewMapper views) {
        this.fx = fx;
        this.replay = replay;
        this.views = views;
    }

    @PostMapping("/rates")
    @Operation(summary = "Publish a rate observation",
            description = """
                    `effectiveAt` is when the price held in the market; `observedAt` is when we learned it and
                    defaults to now. Set `observedAt` explicitly to backfill history without pretending we knew
                    it at the time.
                    """)
    public Responses.FxRateView publish(@Valid @RequestBody Requests.PublishRateRequest request) {
        return views.fxRate(fx.publish(request.baseCurrency(), request.quoteCurrency(), request.rate(),
                request.effectiveAt(), request.observedAt(), request.source()));
    }

    @PostMapping("/rates/corrections")
    @Operation(summary = "Correct a rate we already published",
            description = """
                    Writes a new observation with the same `effectiveAt` and a later `observedAt`. The wrong row
                    is never edited, so any report produced before the correction can still be reproduced
                    exactly as it was.
                    """)
    public Responses.FxRateView correct(@Valid @RequestBody Requests.CorrectRateRequest request) {
        return views.fxRate(fx.publishCorrection(request.supersededRateId(), request.rate(),
                request.observedAt(), request.source()));
    }

    @PostMapping("/rates/corrections/{id}/replay")
    @Operation(summary = "Restate settlements priced off the superseded rate",
            description = """
                    Re-runs the settlement arithmetic with the knowledge time moved forward, which is all a
                    correction is. Customer balances are untouched -- the quoted rate was honoured -- and the
                    difference is posted to the house's FX position, realized P&L, and rounding accounts as a new
                    adjusting transaction beside the original.
                    """)
    public Responses.CorrectionReplayView replay(@PathVariable UUID id,
                                                 @RequestParam(required = false) Instant knownAt) {
        return views.replay(replay.replay(id, knownAt));
    }

    @GetMapping("/rates/resolve")
    @Operation(summary = "Resolve a usable rate for a pair",
            description = """
                    Tries the quoted pair, then the reciprocal of the opposite pair, then composition through the
                    pivot currency. The `resolution` field says which one was used, because a triangulated rate
                    rounds differently from a direct one.
                    """)
    public Responses.RateView resolve(@RequestParam String from,
                                      @RequestParam String to,
                                      @RequestParam(required = false) Instant effectiveAt,
                                      @RequestParam(required = false) Instant knownAt) {
        Instant now = Instant.now();
        return views.rate(fx.resolve(from, to,
                effectiveAt == null ? now : effectiveAt,
                knownAt == null ? now : knownAt));
    }

    @GetMapping("/rates/history")
    @Operation(summary = "Rate history for a pair, as known at a point in time",
            description = "Superseded observations are filtered out, so the series is what we would act on.")
    public List<Responses.FxRateView> history(@RequestParam String from,
                                              @RequestParam String to,
                                              @RequestParam(defaultValue = "90") int days,
                                              @RequestParam(required = false) Instant knownAt) {
        Instant now = Instant.now();
        Instant known = knownAt == null ? now : knownAt;
        List<FxRate> rates = fx.history(from, to, known.minus(Duration.ofDays(days)), known, known);
        return rates.stream().map(views::fxRate).toList();
    }

    @GetMapping("/rates/{id}/corrections")
    @Operation(summary = "Corrections published against a rate")
    public List<Responses.FxRateView> corrections(@PathVariable UUID id) {
        return fx.correctionsOf(id).stream().map(views::fxRate).toList();
    }
}
