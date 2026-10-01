package com.tribule.ledger.web;

import com.tribule.ledger.idempotency.IdempotencyReaper;
import com.tribule.ledger.ledger.BalanceService;
import com.tribule.ledger.verify.LedgerVerificationService;
import com.tribule.ledger.web.dto.Responses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Verification, snapshots, and recovery")
public class AdminController {

    private final LedgerVerificationService verification;
    private final BalanceService balances;
    private final IdempotencyReaper reaper;
    private final ViewMapper views;

    public AdminController(LedgerVerificationService verification, BalanceService balances,
                         IdempotencyReaper reaper, ViewMapper views) {
        this.verification = verification;
        this.balances = balances;
        this.reaper = reaper;
        this.views = views;
    }

    @PostMapping("/verify")
    @Operation(summary = "Prove the books add up",
            description = """
                    Recomputes the trial balance from both the projection and the journal, replays every account's
                    entries, checks that each transaction balances per currency, and cross-checks open
                    authorizations against the hold and contingent accounts backing them.

                    Returns 200 when healthy and 500 when not, so an uptime check can be pointed at it: if this
                    endpoint is failing, nothing else about the service matters.
                    """)
    public ResponseEntity<Responses.VerificationView> verify() {
        Responses.VerificationView report = views.verification(verification.verify());
        return ResponseEntity.status(report.healthy() ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR)
                .body(report);
    }

    @PostMapping("/snapshots")
    @Operation(summary = "Checkpoint every account's balance",
            description = "Bounds the cost of rebuilding a balance to the entries written since the snapshot.")
    public Map<String, Integer> snapshot() {
        return Map.of("snapshotsWritten", balances.snapshotAll());
    }

    @PostMapping("/idempotency/reap")
    @Operation(summary = "Resolve idempotency claims orphaned by a crash",
            description = """
                    For each claim stuck in flight, asks the journal whether its reserved transaction exists. If it
                    does the work landed and the claim is completed; if not it was rolled back and the claim is
                    released so the client can genuinely retry.
                    """)
    public Map<String, Integer> reap() {
        return Map.of("claimsResolved", reaper.reapOnce());
    }
}
