import { sleep, check } from 'k6';
import { getConfig } from './lib/config.js';
import { postOrderWithKey, getOrderById, listProducts } from './lib/endpoints.js';

export const options = {
  scenarios: {
    idempotency_and_saga: {
      executor: 'constant-vus',
      vus: 50,
      duration: '30s',
    },
  },
  thresholds: {
    critical_api_duration: ['p(90)<200', 'p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const config = getConfig();

export default function () {
  const vuId = __VU;
  const iter = __ITER;
  const testMode = iter % 3;

  if (testMode === 0) {
    // 1. Idempotency test: Reusing the same key twice must return 201 with identical body
    const idempotencyKey = `k6-idemp-vu-${vuId}-iter-${iter}`;
    const payload = [{ sku: 'PERF-SKU-00001', quantity: 1 }];

    const res1 = postOrderWithKey(config.appBaseUrl, payload, idempotencyKey);
    check(res1, { 'Idempotent post 1 status is 201': (r) => r.status === 201 });

    const res2 = postOrderWithKey(config.appBaseUrl, payload, idempotencyKey);
    check(res2, {
      'Idempotent post 2 status is 201': (r) => r.status === 201,
      'Idempotent responses match': (r) => r.body === res1.body,
    });
  } else if (testMode === 1) {
    // 2. Saga compensation test: Out of stock SKU triggers cancellation
    const oosKey = `k6-oos-vu-${vuId}-iter-${iter}`;
    const oosPayload = [{ sku: 'SKU-OOS-OUT-OF-STOCK', quantity: 1 }];
    const res = postOrderWithKey(config.appBaseUrl, oosPayload, oosKey);

    check(res, { 'OOS order placed status is 201': (r) => r.status === 201 });
  } else {
    // 3. Catalog browsing
    listProducts(config.appBaseUrl, 0, 20);
  }

  sleep(0.5);
}
