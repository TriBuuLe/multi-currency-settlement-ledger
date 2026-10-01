package com.tribule.ledger.web;

import com.tribule.ledger.recon.ReconciliationService;
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
import org.springframework.web.bind.annotation.RestController;

import java.io.StringReader;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reconciliation")
@Tag(name = "Reconciliation", description = "Match the ledger against external statements and classify the breaks")
public class ReconciliationController {

    private final ReconciliationService reconciliation;
    private final ViewMapper views;

    public ReconciliationController(ReconciliationService reconciliation, ViewMapper views) {
        this.reconciliation = reconciliation;
        this.views = views;
    }

    @PostMapping("/statements")
    @Operation(summary = "Import a statement",
            description = """
                    CSV with the header `external_ref,posted_at,currency,amount_minor,direction,description`.
                    `direction` is DEBIT when our balance went up. Unique on (source, filename), so re-uploading
                    the same file is rejected rather than double-counted.
                    """)
    public Responses.StatementBatchView importStatement(@Valid @RequestBody Requests.ImportStatementRequest request) {
        return views.batch(reconciliation.importStatement(request.source(), request.filename(),
                request.asOfDate(), new StringReader(request.csv())));
    }

    @PostMapping("/statements/{id}/reconcile")
    @Operation(summary = "Reconcile an imported statement",
            description = """
                    Matches on reference, then amount, then timing, and classifies every difference. Duplicates,
                    timing differences, and movements missing from the ledger are resolved automatically --
                    the last of those by posting to suspense, so the books agree with the bank while the
                    unidentified amount stays visible. Amount mismatches and payments the bank has not reported
                    are left open, because closing them would mean guessing.
                    """)
    public Responses.ReconciliationView reconcile(@PathVariable UUID id) {
        return views.reconciliation(reconciliation.reconcile(id));
    }

    @GetMapping("/statements/{id}/breaks")
    @Operation(summary = "Every break found for a statement")
    public List<Responses.BreakView> breaks(@PathVariable UUID id) {
        return views.breaks(reconciliation.breaksFor(id));
    }
}
