#!/usr/bin/env bash
#
# Walks the whole system in one pass, printing what the ledger did at each step.
#
# The sequence is deliberately the one that is hard to get right:
#   1. fund a wallet
#   2. publish a rate, authorize a conversion  -> funds held, obligation off balance sheet
#   3. move the rate, settle                   -> the move is realized into FX P&L
#   4. convert through a pivot                 -> rounding residual posted explicitly
#   5. reconcile against a statement with a break of every kind
#   6. verify                                  -> the books still add up
#
# Usage: ./scripts/demo.sh [base-url]

set -euo pipefail

BASE="${1:-http://localhost:8080}"
CUSTOMER="demo-$(date +%s)"
TODAY="$(date -u +%Y-%m-%d)"
T0="$(date -u -v-2d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '2 days ago' +%Y-%m-%dT%H:%M:%SZ)"
T1="$(date -u -v-1d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '1 day ago' +%Y-%m-%dT%H:%M:%SZ)"

key() { echo "demo-$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)"; }
pretty() { python3 -m json.tool; }
step() { printf '\n\033[1;36m== %s\033[0m\n' "$1"; }

post() {
  local path="$1" body="$2"
  curl -fsS -X POST "$BASE$path" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(key)" \
    -d "$body"
}

step "1. Publish rates (EUR/USD, and the two legs JPY/KWD has to be composed from)"
post /api/v1/fx/rates "{\"baseCurrency\":\"EUR\",\"quoteCurrency\":\"USD\",\"rate\":\"1.10\",\"effectiveAt\":\"$T0\",\"observedAt\":\"$T0\",\"source\":\"demo\"}" | pretty
post /api/v1/fx/rates "{\"baseCurrency\":\"JPY\",\"quoteCurrency\":\"USD\",\"rate\":\"0.0063572\",\"effectiveAt\":\"$T0\",\"observedAt\":\"$T0\",\"source\":\"demo\"}" > /dev/null
post /api/v1/fx/rates "{\"baseCurrency\":\"USD\",\"quoteCurrency\":\"KWD\",\"rate\":\"0.30712\",\"effectiveAt\":\"$T0\",\"observedAt\":\"$T0\",\"source\":\"demo\"}" > /dev/null
echo "(JPY/KWD is quoted nowhere; it will be composed through USD)"

step "2. Fund the customer with 1,000.00 EUR and 1,000,000 JPY"
post /api/v1/payments/funding "{\"customerId\":\"$CUSTOMER\",\"currency\":\"EUR\",\"amountMinor\":100000,\"reference\":\"demo-fund-eur\"}" | pretty
post /api/v1/payments/funding "{\"customerId\":\"$CUSTOMER\",\"currency\":\"JPY\",\"amountMinor\":1000000,\"reference\":\"demo-fund-jpy\"}" > /dev/null

step "3. Authorize EUR -> USD at 1.10. Funds are held; no USD moves yet."
AUTH=$(post /api/v1/authorizations "{\"reference\":\"demo-auth-$CUSTOMER\",\"customerId\":\"$CUSTOMER\",\"sellCurrency\":\"EUR\",\"buyCurrency\":\"USD\",\"sellAmountMinor\":100000,\"ttlSeconds\":2592000}")
echo "$AUTH" | pretty
AUTH_ID=$(echo "$AUTH" | python3 -c 'import json,sys; print(json.load(sys.stdin)["authorization"]["id"])')

step "4. Settlement risk while the authorization is open"
curl -fsS "$BASE/api/v1/risk/exposure" | pretty

step "5. The rate moves to 1.15, then we settle"
post /api/v1/fx/rates "{\"baseCurrency\":\"EUR\",\"quoteCurrency\":\"USD\",\"rate\":\"1.15\",\"effectiveAt\":\"$T1\",\"observedAt\":\"$T1\",\"source\":\"demo\"}" > /dev/null
echo "The customer is paid the 1.10 they were quoted. The difference is the house's."
post "/api/v1/authorizations/$AUTH_ID/settlement" '{}' | pretty

step "6. Convert JPY -> KWD, composed through USD. Watch roundingResidual."
post /api/v1/payments/conversions "{\"customerId\":\"$CUSTOMER\",\"sellCurrency\":\"JPY\",\"buyCurrency\":\"KWD\",\"sellAmountMinor\":123457,\"reference\":\"demo-triangulated\"}" | pretty

step "7. Reconcile against a statement containing one of every kind of break"
STATEMENT="external_ref,posted_at,currency,amount_minor,direction,description
demo-fund-eur,${T0},EUR,100000,DEBIT,matches cleanly
demo-fund-eur,${T0},EUR,100000,DEBIT,the bank sent this reference twice
demo-unknown-$CUSTOMER,${T0},EUR,4200,DEBIT,money we never booked
demo-fund-jpy,${T0},JPY,999999,DEBIT,amount disagrees with the ledger"
BATCH=$(curl -fsS -X POST "$BASE/api/v1/reconciliation/statements" \
  -H 'Content-Type: application/json' \
  -d "$(python3 -c '
import json, sys
print(json.dumps({
    "source": "demo-bank",
    "filename": sys.argv[1] + ".csv",
    "asOfDate": sys.argv[2],
    "csv": sys.argv[3],
}))' "$CUSTOMER" "$TODAY" "$STATEMENT")")
BATCH_ID=$(echo "$BATCH" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl -fsS -X POST "$BASE/api/v1/reconciliation/statements/$BATCH_ID/reconcile" | pretty

step "8. Balances"
curl -fsS "$BASE/api/v1/accounts/LIABILITY:CUSTOMER:$CUSTOMER:USD/balance" | pretty
curl -fsS "$BASE/api/v1/accounts/EQUITY:FX_REALIZED_PNL:USD/balance" | pretty
curl -fsS "$BASE/api/v1/accounts/EQUITY:FX_ROUNDING:KWD/balance" | pretty

step "9. The trial balance: zero in every currency, or the books are wrong"
curl -fsS "$BASE/api/v1/trial-balance" | pretty

step "10. Full verification"
curl -fsS -X POST "$BASE/api/v1/admin/verify" | pretty

printf '\n\033[1;32mdone.\033[0m customer id was %s\n' "$CUSTOMER"
