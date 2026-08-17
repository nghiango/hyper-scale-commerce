import http from 'k6/http';
import { sleep, check } from 'k6';
import { getConfig } from './lib/config.js';
import { getProductById, listProducts, postOrderWithKey, getOrderById } from './lib/endpoints.js';

export const options = {
  scenarios: {
    stream_resilience: {
      executor: 'constant-vus',
      vus: 50,
      duration: '30s',
    },
  },
  thresholds: {
    critical_api_duration: ['p(90)<50', 'p(95)<100'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const config = getConfig();

export default function () {
  const vuId = __VU;
  const iter = __ITER;
  const mod = iter % 10;

  if (mod < 6) {
    // 60% Read traffic
    const productId = ((iter + vuId) % 100) + 1;
    getProductById(config.appBaseUrl, productId);
  } else if (mod < 8) {
    // 20% Catalog List
    listProducts(config.appBaseUrl, 0, 20);
  } else {
    // 20% Order checkout with idempotency key
    const sku = `PERF-SKU-${(((iter + vuId) % 50) + 1).toString().padStart(5, '0')}`;
    const idempotencyKey = `STREAM-KEY-VU${vuId}-IT${iter}`;
    const { orderId } = postOrderWithKey(config.appBaseUrl, [{ sku, quantity: 1 }], idempotencyKey);
    if (orderId) {
      getOrderById(config.orderQueryBaseUrl, orderId, { polling: 'true' });
    }
  }

  sleep(0.1);
}
