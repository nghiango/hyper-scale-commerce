import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const PEAK_VUS = Number.parseInt(__ENV.PEAK_VUS || '5000', 10);
const WARM_VUS = Number.parseInt(__ENV.WARM_VUS || '500', 10);
const RAMP_DURATION = __ENV.RAMP_DURATION || '60s';
const STEADY_DURATION = __ENV.STEADY_DURATION || '120s';
const COOLDOWN_DURATION = __ENV.COOLDOWN_DURATION || '30s';
const PACING_SECONDS = Number.parseFloat(__ENV.PACING_SECONDS || '1.5');
const WRITE_PERCENT = Number.parseFloat(__ENV.WRITE_PERCENT || '0.01');
const FAULT_WINDOW_START_SECONDS = Number.parseFloat(__ENV.FAULT_WINDOW_START_SECONDS || '125');
const FAULT_WINDOW_END_SECONDS = Number.parseFloat(__ENV.FAULT_WINDOW_END_SECONDS || '180');

if (PEAK_VUS < 5000 && __ENV.ALLOW_SUBQUALIFICATION !== 'true') {
  throw new Error(`Phase 17 qualification requires PEAK_VUS >= 5000; received ${PEAK_VUS}`);
}

const catalogLatency = new Trend('cache_catalog_read_duration', true);
const orderQueryLatency = new Trend('replica_order_query_duration', true);
const faultOrderQueryLatency = new Trend('fault_order_query_duration', true);
const orderCreationLatency = new Trend('primary_order_creation_duration', true);
const errorRate = new Rate('cache_error_rate');
const failedRequests = new Counter('cache_failed_requests');
const successfulOrders = new Counter('cache_successful_orders');

export const options = {
  scenarios: {
    cache_replica_scale: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP_DURATION, target: WARM_VUS },
        { duration: RAMP_DURATION, target: PEAK_VUS },
        { duration: STEADY_DURATION, target: PEAK_VUS },
        { duration: COOLDOWN_DURATION, target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    'cache_catalog_read_duration': ['p(95)<10'],
    'replica_order_query_duration': ['p(95)<20'],
    'primary_order_creation_duration': ['p(95)<200'],
    'cache_error_rate': ['rate<0.01'],
    'cache_failed_requests': ['count==0'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';
const QUERY_URL = __ENV.QUERY_URL || 'http://localhost:8081';

export default function () {
  const rand = Math.random();

  // Read-heavy qualification: 44% catalog, 55% order query, 1% writes by default.
  // This keeps app traffic below its 60,000 request/minute protection limit at
  // the phase target of more than 2,000 aggregate RPS.
  const catalogBoundary = 0.45 - WRITE_PERCENT;
  const orderQueryBoundary = 1 - WRITE_PERCENT;
  if (rand < catalogBoundary) {
    const res = http.get(`${BASE_URL}/catalog/products`, {
      headers: { 'Accept': 'application/json' },
    });
    catalogLatency.add(res.timings.duration);
    const ok = check(res, {
      'catalog status 200': (r) => r.status === 200,
    });
    if (!ok) {
      errorRate.add(1);
      failedRequests.add(1);
    } else errorRate.add(0);
  }
  // 2. Order Query (55% traffic - read replica)
  else if (rand < orderQueryBoundary) {
    const res = http.get(`${QUERY_URL}/orders`, {
      headers: { 'Accept': 'application/json' },
    });
    const elapsedSeconds = exec.instance.currentTestRunDuration / 1000;
    if (elapsedSeconds >= FAULT_WINDOW_START_SECONDS && elapsedSeconds <= FAULT_WINDOW_END_SECONDS) {
      faultOrderQueryLatency.add(res.timings.duration);
    } else {
      orderQueryLatency.add(res.timings.duration);
    }
    const ok = check(res, {
      'order query status 200': (r) => r.status === 200,
    });
    if (!ok) {
      errorRate.add(1);
      failedRequests.add(1);
    } else errorRate.add(0);
  }
  // 3. Order Creation (1% traffic - Patroni primary write)
  else {
    const skuNum = Math.floor(Math.random() * 100) + 1;
    const sku = `PROD-${String(skuNum).padStart(6, '0')}`;
    const payload = JSON.stringify({
      items: [{ sku: sku, quantity: 1 }],
    });

    const res = http.post(`${BASE_URL}/orders`, payload, {
      headers: { 'Content-Type': 'application/json' },
    });
    orderCreationLatency.add(res.timings.duration);
    const ok = check(res, {
      'order created 200 or 201': (r) => r.status === 200 || r.status === 201,
    });
    if (ok) {
      successfulOrders.add(1);
      errorRate.add(0);
    } else {
      errorRate.add(1);
      failedRequests.add(1);
    }
  }

  sleep(PACING_SECONDS);
}

export function handleSummary(data) {
  return {
    stdout: `PHASE17_SUMMARY_JSON=${JSON.stringify(data)}\n`,
  };
}
