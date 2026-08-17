import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const orderCreationLatency = new Trend('k8s_order_creation_duration', true);
const orderQueryLatency = new Trend('k8s_order_query_duration', true);
const catalogLatency = new Trend('k8s_catalog_duration', true);
const errorRate = new Rate('k8s_error_rate');
const successfulOrders = new Counter('k8s_successful_orders');

export const options = {
  scenarios: {
    k8s_qualification: {
      executor: 'ramping-vus',
      startVUs: 5,
      stages: [
        { duration: '10s', target: 20 },  // Steady State Warmup
        { duration: '15s', target: 100 }, // 5x Spike (HPA Scale-Out Trigger)
        { duration: '20s', target: 50 },  // Node Drain & Ingress Failover Window
        { duration: '15s', target: 20 },  // Post-Failover Steady State
        { duration: '5s', target: 0 },    // Scale-In Ramp Down
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    'k8s_catalog_duration': ['p(95)<200'],
    'k8s_order_creation_duration': ['p(95)<200'],
    'k8s_order_query_duration': ['p(95)<200'],
    'k8s_error_rate': ['rate<0.02'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';
const QUERY_URL = __ENV.QUERY_URL || 'http://localhost:8081';

export default function () {
  const rand = Math.random();

  // 1. Catalog Read (50% traffic)
  if (rand < 0.50) {
    const res = http.get(`${BASE_URL}/catalog`, {
      headers: { 'Accept': 'application/json' },
    });
    catalogLatency.add(res.timings.duration);
    const ok = check(res, {
      'catalog status 200': (r) => r.status === 200,
    });
    if (!ok) errorRate.add(1);
    else errorRate.add(0);
  }
  // 2. Order Creation (30% traffic)
  else if (rand < 0.80) {
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
      'order creation status 200 or 201': (r) => r.status === 200 || r.status === 201,
    });
    if (ok) {
      successfulOrders.add(1);
      errorRate.add(0);
    } else {
      errorRate.add(1);
    }
  }
  // 3. Order Query (20% traffic)
  else {
    const res = http.get(`${QUERY_URL}/orders`, {
      headers: { 'Accept': 'application/json' },
    });
    orderQueryLatency.add(res.timings.duration);
    const ok = check(res, {
      'order query status 200': (r) => r.status === 200,
    });
    if (!ok) errorRate.add(1);
    else errorRate.add(0);
  }

  sleep(0.05);
}
