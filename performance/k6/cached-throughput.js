import { sleep, check } from 'k6';
import { getConfig } from './lib/config.js';
import { getProductById, listProducts, postOrder, getOrderById } from './lib/endpoints.js';

export const options = {
  scenarios: {
    cached_throughput: {
      executor: 'constant-vus',
      vus: 100,
      duration: '30s',
    },
  },
  thresholds: {
    critical_api_duration: ['p(90)<50', 'p(95)<100'],
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const config = getConfig();

export default function () {
  const vuId = __VU;
  const iter = __ITER;
  const mod = iter % 10;

  if (mod < 7) {
    // 70% Product lookup (High cache hit probability: hot subset 1..100)
    const productId = ((iter + vuId) % 100) + 1;
    getProductById(config.appBaseUrl, productId);
  } else if (mod < 9) {
    // 20% Catalog list
    const page = iter % 5;
    listProducts(config.appBaseUrl, page, 20);
  } else {
    // 10% Buyer checkout
    const sku = `PERF-SKU-${(((iter + vuId) % 50) + 1).toString().padStart(5, '0')}`;
    const { orderId } = postOrder(config.appBaseUrl, [{ sku, quantity: 1 }]);
    if (orderId) {
      // Query order immediately to populate cache
      getOrderById(config.orderQueryBaseUrl, orderId, { polling: 'true' });
    }
  }

  sleep(0.1);
}
