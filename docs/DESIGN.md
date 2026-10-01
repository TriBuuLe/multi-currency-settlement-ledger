# Design notes

Why this is built the way it is, including the alternatives that were considered and
rejected. The [README](../README.md) describes what the system does; this is the reasoning.

---

## 1. The guarantee belongs in the database

The central decision. Every invariant that matters is enforced by PostgreSQL, not by
application code.

The usual objection is that business rules belong in the service layer. For most rules that is
right. For *conservation of money* it is not, because the cost of being wrong is unbounded and
the number of ways to be wrong grows with every code path added later: a new endpoint, a
backfill script, a data migration, an engineer fixing a production incident at 2am with `psql`.
Application-layer validation protects the paths that go through it. A constraint protects all
of them, including the ones that don't exist yet.

So:

```sql
CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();
```

`DEFERRABLE INITIALLY DEFERRED` is load-bearing. The check runs at `COMMIT`, not per row —
otherwise the first leg of any two-leg transaction would fail on its own and nothing could ever
be written. Deferral is what lets a transaction be legally unbalanced mid-flight and illegal at
commit.

**Cost, honestly.** A constraint trigger must be `FOR EACH ROW`, so an eight-leg settlement
re-runs the aggregate eight times at commit. With at most a handful of legs per transaction
that is cheap, and the benchmark (5,100 tps on uncontended accounts) says it is not the
bottleneck. If it ever became one, the fix is a statement-level deferred check, which Postgres
doesn't offer directly and would mean accumulating touched transaction ids in a transaction-local
table — more machinery for a problem that doesn't exist yet.

### Surfacing the failure usefully

A deferred trigger fires at commit — after the service method has returned — so the caller gets
an opaque transaction-rollback error instead of something actionable. The fix is one line at the
end of the write:

```java
journal.flushDeferredConstraints();   // SET CONSTRAINTS ALL IMMEDIATE
```

That forces every deferred constraint to be checked *now*, while we are still inside the
transaction and still hold the context to say "insufficient funds in
`LIABILITY:CUSTOMER:alice:USD`: balance would be -2,500". The guarantee is unchanged; only the
error message improves.

### Application checks anyway

`LedgerService` also verifies balance before writing. That is not redundancy for its own sake:
the application check produces a precise message naming the currency and the amount it is out
by, which is what an API consumer needs. The database check is the guarantee. Two different
jobs.

---

## 2. Signed balances, and the invariant that falls out

`account_balance.balance_minor` is stored as **debit minus credit**, not in each account's
natural direction. That seems to make reads worse — a liability with money in it has a negative
stored balance — and one line in the API layer fixes that for presentation.

What it buys is a single global invariant that is trivially checkable:

```
for every currency:  SUM(balance_minor) over all accounts = 0
```

No case analysis by account type, no sign table, no exceptions. It is one `SUM`, it is the
trial balance, it is a Prometheus gauge, and it is asserted at the end of nearly every test. A
per-type sign convention would have made each individual read marginally nicer and this
property impossible to state in one line.

---

## 3. Two currencies in one transaction

A conversion cannot balance "overall" — there is no rate at which ¥1,000,000 *equals* €6,000 for
accounting purposes, and inventing one would mean the books depend on a price. It balances in
each currency **independently**, which is possible only because the two halves never reference
each other's units. They meet at `EQUITY:FX_POSITION`:

```
EUR half:  Dr customer hold EUR    Cr FX position EUR
USD half:  Dr FX position USD      Cr customer wallet USD (+ P&L, + rounding)
```

Neither half contains the other's currency, so neither can be unbalanced by a rate. The
constraint trigger groups by `(transaction_id, currency_code)` for exactly this reason.

`EQUITY:FX_POSITION` is not a bookkeeping fiction — it accumulates the house's real FX position,
which a production system would sweep and hedge. That is noted as out of scope rather than
pretended away.

### Where the rate move goes, and why it gets its own account

The customer is paid the rate they were quoted; the market pays something else at settlement.
The difference is not an error to round away, it is the economic result of having carried the
position for two days:

```
realized P&L = convert(sellAmount, settlement rate) - quotedAmount
```

It is posted to `EQUITY:FX_REALIZED_PNL` in the buy currency. The alternative — netting it into
the customer's balance, or into a generic "other income" account — would make the books balance
just as well and would destroy the only signal that says whether the spread charged on quotes
is adequate. Giving it an account is the difference between a ledger that balances and a ledger
that can be reasoned about.

### Rounding

Composing a rate through a pivot means rounding the intermediate leg to a bookable amount
before applying the second, so the two-leg result differs from the exact cross rate. The gap is
whole minor units of real money:

```
booked + residual = market value
```

The residual goes to `EQUITY:FX_ROUNDING`, separate from P&L, so rounding drift stays
distinguishable from trading result. `FxMath.allocate` uses the largest-remainder method for the
same reason: rounding *n* shares independently loses or invents minor units, and handing the
leftovers to the largest fractional parts is both fair and exact.

`HALF_EVEN` rather than `HALF_UP` throughout. `HALF_UP` is biased upward; applied to millions of
conversions that bias is a real and auditable transfer of value.

---

## 4. Concurrency: why `READ COMMITTED` is enough

The dangerous bug in a ledger is not a crash. It is two concurrent withdrawals both reading a
balance of 100, both deciding 100 is enough, and both succeeding. A read-then-write in
application code has exactly that race and it is invisible in single-threaded tests.

The design makes the race structurally impossible without any application locking. The balance
projection is maintained by an upsert on **one row per account**:

```sql
INSERT INTO account_balance AS b (...) VALUES (...)
ON CONFLICT (account_id) DO UPDATE SET balance_minor = b.balance_minor + signed_delta, ...
```

Two transactions posting to the same account contend on that row, so Postgres serialises them
on a row lock held until commit. The second cannot proceed until the first commits, and then it
sees the committed balance. The overdraft check runs at commit, under that same still-held lock,
so nothing can slip between the check and the commit.

Consequences:

- **No `SERIALIZABLE`, no `SELECT … FOR UPDATE`, no advisory locks.** The lock is a side effect
  of maintaining the projection, which has to happen anyway.
- **Zero retries in practice.** Row locks *wait*; they do not abort. There is nothing to retry,
  which the benchmark confirms (0 retries across 3,840 concurrent writes).
- **Per-account serialisation is the throughput limit.** Measured at 1,887 tps on one hot
  account versus 5,133 tps spread across many — a ~2.7× gap that is the cost of correctness
  here, and the first thing to attack if this needed to go faster.

[`TransactionalRetry`](../src/main/java/com/tribule/ledger/ledger/TransactionalRetry.java) still
exists, for deadlocks and lock timeouts, with exponential backoff and full jitter so retriers
don't re-collide in lockstep. It wraps the transactional method from the outside, because a
rolled-back transaction cannot be resumed, only re-run. `ledger_retry_attempts_total` reports
whether it is ever needed.

### The ordering bug this design made easy to get wrong

Settlement originally posted its ledger entries and *then* claimed the state transition with a
version check. Functionally safe — only one settlement ever landed — but the loser's postings
drove the hold account negative first, so it failed with "insufficient funds": true, and
completely misleading.

The claim now comes first. That required making `settlement_transaction_id` a **deferred**
foreign key, since the row briefly points at a transaction written moments later. The general
lesson is worth stating: claim the right to do the work before doing it, and let the foreign key
be deferred rather than reordering the work to suit the constraint.

---

## 5. Idempotency, including the crash

Uniqueness is decided by a primary key on `(scope, idempotency_key)`. Two simultaneous retries
both attempt an `INSERT`; exactly one can win. A read-then-write would have the same race as the
balance check.

The claim must **commit before the work starts** — otherwise a concurrent retry cannot see it and
both copies run. That creates the interesting failure mode: if the process dies after the work
commits but before the claim is marked complete, the key is stuck `IN_FLIGHT` and the client is
told "still in progress" forever.

The fix is to make recovery a question the journal can answer. The claim stores the transaction
id *reserved* for the work, before the work runs. The reaper then asks: does that transaction
exist?

- **Yes** → the work landed; complete the claim.
- **No** → it rolled back; release the claim for a genuine retry.

This is why `idempotency_record.transaction_id` is deliberately **not** a foreign key — a point
the tests made concrete by failing. It is a reservation, not a reference; at write time the
transaction does not exist, and if the work rolls back it never will.

Three outcomes, all deliberate:

| Situation | Response |
|---|---|
| first arrival | does the work, stores the response |
| same key, same body | replays the stored response, `Idempotent-Replay: true` |
| same key, **different** body | `422`. Never papered over — returning the first result for a different request is how a $10 transfer gets reported as a successful $10,000 one |

The fingerprint is a SHA-256 of canonical JSON with sorted keys, so a client that serialises its
fields in a different order on retry is still recognised as the same request.

---

## 6. Bitemporality: two timestamps, not one

```sql
effective_at  TIMESTAMPTZ NOT NULL,  -- when the price held in the market
observed_at   TIMESTAMPTZ NOT NULL,  -- when we learned it
```

The question that needs both: *what did we believe the EUR/USD rate was last Tuesday, using only
what we knew then?* With one timestamp it is unanswerable, and every historical report silently
changes its answer each time it runs as corrections leak backwards into it.

The lookup is one index scan:

```sql
WHERE base_currency = ? AND quote_currency = ?
  AND effective_at <= ?   -- valid time
  AND observed_at  <= ?   -- knowledge time
ORDER BY effective_at DESC, observed_at DESC
LIMIT 1
```

Dropping the second predicate is the bug this schema exists to prevent.

A correction is a new row with the same `effective_at`, a later `observed_at`, and
`supersedes_id` pointing at what it replaces. The wrong row is never edited — `fx_rate` has the
same append-only trigger as the journal.

**The payoff.** Restating a settlement needs no correction-specific logic at all. It is the
ordinary settlement calculation with one input changed:

```java
resolve(pair, effectiveAt = settledAt, knownAt = settledAt);  // what we did
resolve(pair, effectiveAt = settledAt, knownAt = now);        // what we now know
```

Customer balances are untouched by replay. They were quoted a rate and paid that rate; a
vendor's bad tick is the house's problem. Only the house's FX position, realized P&L, and
rounding accounts move, as a new adjusting transaction beside the original.

---

## 7. Append-only, and correcting by reversal

```sql
CREATE TRIGGER journal_entry_append_only
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
```

`UPDATE` and `DELETE` both raise, and so does `TRUNCATE`. Corrections are reversing transactions
that link back via `reverses_transaction_id`, which preserves a distinction an editable ledger
destroys: **"this never happened" and "this happened and was undone" are different facts**, and
an auditor needs to be able to tell them apart.

`fx_authorization` is deliberately *not* append-only. It is a state aggregate — a projection of
decisions whose history lives in the journal — and its status genuinely transitions. Mixing the
two would mean either an immutable table that cannot represent state, or a journal that can be
edited. Keeping them separate is why the verifier's cross-domain check exists: each subsystem
is self-consistent alone, and only check #6 catches them drifting apart.

This does make test isolation harder — nothing can be truncated between tests. Rather than
weakening the constraint for testing convenience, tests generate unique customer ids and
operate in disjoint 400-day timeline windows. The constraint is the product; the test setup
adapts.

---

## 8. Explicit SQL instead of an ORM

Spring's `JdbcClient`, not JPA. The reasons are specific, not stylistic:

- An append-only table with database-side constraint triggers fights an ORM that wants to manage
  entity state, flush order, and dirty checking.
- The interesting operations here are not entity graph traversals. They are a multi-row
  `INSERT … RETURNING` (one round trip for all eight legs of a settlement instead of eight), an
  `ON CONFLICT` upsert whose row lock *is* the concurrency control, a bitemporal lookup, a
  snapshot-anchored replay in a CTE, and `SET CONSTRAINTS ALL IMMEDIATE`. Each is clearer written
  out than configured.
- Reference data is cached in memory instead: accounts and currency scales are immutable once
  created and every posting needs at least two accounts, so re-reading them on the hot path is
  the easiest throughput loss to avoid.

---

## 9. Performance notes

| Decision | Why |
|---|---|
| One multi-row `INSERT … RETURNING` for all legs | a settlement is eight legs; per-leg round trips would make network latency the dominant cost of posting |
| Accounts and currencies cached by code | immutable after creation, and read on every single write |
| Balance snapshots | rebuilding a balance is `O(entries since snapshot)`, not `O(all history)` — the difference between a 50 ms check and a full scan a year in |
| Partial indexes on pending authorizations | the hot queries are "what is still open?"; indexing the closed rows too would be mostly waste |
| Pool size 16, not 100 | past the point where Postgres can run queries in parallel, more connections add contention and lock-wait, not throughput |
| Metrics refreshed on a timer | computing per-scrape means three Prometheus replicas turn a dashboard into load |

Where this would go next, in order: partition `journal_entry` by month (the table only grows, and
every query is already account- or transaction-scoped); shard hot accounts into *N* sub-accounts
with fan-in on read, which is the direct fix for the 2.7× hot-account penalty; add a
transactional outbox once something downstream actually consumes events.

---

## 10. Things left undone, and why

- **No authentication.** Real work, but it would not make the ledger more correct.
- **No outbound events.** A transactional outbox is the right pattern; with no consumer it would
  be unexercised code, which is worse than an acknowledged gap.
- **FX position is not hedged.** The accounting is complete; the treasury function is a different
  system.
- **The VaR is a simple historical simulation.** No volatility model, no correlation across
  pairs. Every estimate carries its limits in a `method` field, and the portfolio figure states
  that it sums per-pair numbers with no diversification benefit. A number whose assumptions
  travel with it is more useful than a sophisticated one whose don't.
- **Single node.** Section 9 says where the seams would be.
