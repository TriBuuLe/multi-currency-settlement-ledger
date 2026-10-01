package com.tribule.ledger.web;

import com.tribule.ledger.risk.ExposureService;
import com.tribule.ledger.web.dto.Responses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/risk")
@Tag(name = "Risk", description = "Settlement risk carried between authorization and settlement")
public class RiskController {

    private final ExposureService exposure;
    private final ViewMapper views;

    public RiskController(ExposureService exposure, ViewMapper views) {
        this.exposure = exposure;
        this.views = views;
    }

    @GetMapping("/exposure")
    @Operation(summary = "Mark open authorizations to market",
            description = """
                    Runs the same arithmetic settlement runs, with the current rate substituted for the
                    settlement rate, so unrealized and realized P&L are the same measurement at two points in
                    time rather than two independent models that can disagree.

                    Also reports the net position per currency and a one-day historical-simulation VaR. The VaR's
                    assumptions and limits are stated in each estimate's `method` field; the portfolio figure sums
                    the per-pair numbers and claims no correlation benefit.
                    """)
    public Responses.ExposureView exposure(@RequestParam(required = false) Instant asOf) {
        return views.exposure(exposure.report(asOf));
    }
}
