package com.tribule.ledger.web;

import com.tribule.ledger.idempotency.IdempotencyService;
import com.tribule.ledger.ledger.AccountRepository;
import com.tribule.ledger.ledger.BalanceService;
import com.tribule.ledger.ledger.LedgerService;
import com.tribule.ledger.ledger.TransactionalRetry;
import com.tribule.ledger.web.dto.Requests;
import com.tribule.ledger.web.dto.Responses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Ledger", description = "Accounts, balances, the trial balance, and the journal")
public class LedgerController {

    private final AccountRepository accounts;
    private final BalanceService balances;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final TransactionalRetry retry;
    private final ViewMapper views;

    public LedgerController(AccountRepository accounts, BalanceService balances, LedgerService ledger,
                           IdempotencyService idempotency, TransactionalRetry retry, ViewMapper views) {
        this.accounts = accounts;
        this.balances = balances;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.retry = retry;
        this.views = views;
    }

    @GetMapping("/accounts")
    @Operation(summary = "List the chart of accounts")
    public List<Responses.AccountView> listAccounts(@RequestParam(required = false) String currency) {
        return (currency == null ? accounts.findAll() : accounts.findByCurrency(currency.toUpperCase()))
                .stream().map(views::account).toList();
    }

    @GetMapping("/accounts/{code}/balance")
    @Operation(summary = "Read one account's balance",
            description = """
                    `signedBalanceMinor` is debit-minus-credit, which is the convention that makes the trial
                    balance sum to zero. `normalBalanceMinor` flips the sign for credit-normal accounts so a
                    liability with money in it reads positive.
                    """)
    public Responses.BalanceView balance(@PathVariable String code) {
        return views.balance(balances.balanceOf(code));
    }

    @GetMapping("/accounts/{code}/rebuild")
    @Operation(summary = "Recompute an account's balance from the journal",
            description = """
                    Replays the entries from the account's newest snapshot and reports whether the answer matches
                    the stored projection. This is the proof that the projection is derived rather than
                    authoritative.
                    """)
    public Responses.RebuildView rebuild(@PathVariable String code) {
        return views.rebuild(code, balances.balanceOf(code).signedBalanceMinor(), balances.rebuild(code));
    }

    @GetMapping("/balances")
    @Operation(summary = "All balances, or all balances in one currency")
    public List<Responses.BalanceView> balances(@RequestParam(required = false) String currency) {
        return (currency == null ? balances.allBalances() : balances.balancesForCurrency(currency.toUpperCase()))
                .stream().map(views::balance).toList();
    }

    @GetMapping("/trial-balance")
    @Operation(summary = "The trial balance per currency",
            description = "`residualMinor` must be zero in every currency. If it is not, the books are wrong.")
    public List<Responses.TrialBalanceView> trialBalance() {
        return balances.trialBalance().stream().map(views::trialBalance).toList();
    }

    @GetMapping("/transactions/{id}")
    @Operation(summary = "Read a transaction and its entries")
    public Responses.TransactionView transaction(@PathVariable UUID id) {
        return views.transaction(ledger.require(id));
    }

    @PostMapping("/transactions/{id}/reversal")
    @Operation(summary = "Reverse a transaction",
            description = """
                    Posts the mirror image as a new transaction. Nothing is edited or deleted, so the journal
                    still shows that the original happened and was undone -- which is a different fact from it
                    never having happened.
                    """)
    public ResponseEntity<Responses.TransactionView> reverse(
            @RequestHeader("Idempotency-Key") String key,
            @PathVariable UUID id,
            @Valid @RequestBody Requests.ReverseRequest request) {
        IdempotencyService.Outcome<Responses.TransactionView> outcome = idempotency.execute(
                "ledger.reversal." + id, key, request, Responses.TransactionView.class,
                transactionId -> retry.execute("reverse", () -> views.transaction(
                        ledger.reverse(id, request.reason(),
                                request.occurredAt() == null ? java.time.Instant.now() : request.occurredAt()))));
        return ResponseEntity.ok()
                .header("Idempotent-Replay", Boolean.toString(outcome.replayed()))
                .body(outcome.value());
    }
}
