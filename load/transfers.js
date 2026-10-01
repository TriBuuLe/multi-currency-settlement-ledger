// k6 load profile for the ledger's write path.
//
// Two scenarios, because they stress different things:
//
//   spread   transfers scattered across many wallets. This is the easy case: distinct
//            accounts mean distinct balance rows, so there is almost no contention and
//            throughput is bounded by the database's commit rate.
//
//   hot      every transfer touches the SAME wallet. All of them contend on one
//            balance row, so they serialise. This is the number that matters, because
//            real systems always have one account everything flows through, and it is
//            where a naive implementation either deadlocks or silently double-spends.
//
// Run:   make up && k6 run load/transfers.js
// Then:  curl -s localhost:8080/api/v1/trial-balance    <- must still be zero
//
// The point of the run is not the throughput figure on its own. It is that the trial
// balance is still zero afterwards.

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const RUN = __ENV.RUN_ID || `load-${Date.now()}`;

const insufficientFunds = new Counter('ledger_insufficient_funds');
const conflicts = new Counter('ledger_conflicts');

export const options = {
  scenarios: {
    spread: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.SPREAD_RPS || 200),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 40,
      maxVUs: 200,
      exec: 'spreadTransfer',
    },
    hot: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.HOT_RPS || 60),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 20,
      maxVUs: 100,
      exec: 'hotAccountTransfer',
      startTime: '35s',
    },
  },
  thresholds: {
    // Correctness first: no 5xx at all. A ledger that drops writes under load is
    // worse than one that is slow.
    'http_req_failed{scenario:spread}': ['rate<0.01'],
    'http_req_duration{scenario:spread}': ['p(95)<250', 'p(99)<600'],
    // The hot account is allowed to be slower -- it is serialised by design -- but it
    // must not start failing.
    'http_req_duration{scenario:hot}': ['p(99)<1500'],
  },
};

function headers() {
  return {
    'Content-Type': 'application/json',
    // A fresh key per request: this measures distinct writes, not replays.
    'Idempotency-Key': `${RUN}-${__VU}-${__ITER}-${Math.random().toString(36).slice(2)}`,
  };
}

function post(path, body) {
  const response = http.post(`${BASE}${path}`, JSON.stringify(body), { headers: headers() });
  if (response.status === 409) {
    if (String(response.body).includes('nsufficient')) {
      insufficientFunds.add(1);
    } else {
      conflicts.add(1);
    }
  }
  check(response, { 'no server error': (r) => r.status < 500 });
  return response;
}

// Each VU funds its own pair of wallets once, so the measured requests are transfers
// rather than account creation.
export function setup() {
  const treasury = `${RUN}-treasury`;
  const hot = `${RUN}-hot`;
  for (const customer of [treasury, hot]) {
    http.post(
      `${BASE}/api/v1/payments/funding`,
      JSON.stringify({
        customerId: customer,
        currency: 'USD',
        amountMinor: 1000000000,
        reference: `${RUN}-seed-${customer}`,
      }),
      { headers: headers() },
    );
  }
  return { treasury, hot };
}

export function spreadTransfer(data) {
  const from = `${RUN}-w${__VU}`;
  http.post(
    `${BASE}/api/v1/payments/funding`,
    JSON.stringify({ customerId: from, currency: 'USD', amountMinor: 100000, reference: `${RUN}-top-${__VU}-${__ITER}` }),
    { headers: headers() },
  );
  post('/api/v1/payments/transfers', {
    fromCustomerId: from,
    toCustomerId: `${RUN}-w${(__VU % 50) + 100}`,
    currency: 'USD',
    amountMinor: 100,
    reference: `${RUN}-spread-${__VU}-${__ITER}`,
  });
}

// Every iteration debits one wallet. All of them queue on the same balance row.
export function hotAccountTransfer(data) {
  post('/api/v1/payments/transfers', {
    fromCustomerId: data.hot,
    toCustomerId: `${RUN}-sink${__VU % 10}`,
    currency: 'USD',
    amountMinor: 1,
    reference: `${RUN}-hot-${__VU}-${__ITER}`,
  });
}

export function teardown() {
  const trialBalance = http.get(`${BASE}/api/v1/trial-balance`);
  const lines = JSON.parse(trialBalance.body);
  const unbalanced = lines.filter((line) => line.residualMinor !== 0);
  if (unbalanced.length > 0) {
    throw new Error(`trial balance is not zero after the run: ${JSON.stringify(unbalanced)}`);
  }
  console.log(`trial balance is zero in all ${lines.length} currencies after the run`);
}
