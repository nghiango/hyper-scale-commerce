import { getConfig } from './lib/config.js';
import { windowShopperJourney, buyerJourney, returningCustomerJourney } from './lib/journeys.js';

export const options = {
  scenarios: {
    chaos_kafka: {
      executor: 'constant-vus',
      vus: 50,
      duration: '40s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'], // Outbox ensures 100% order write durability even with Kafka down
    critical_api_duration: ['p(95)<200'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const config = getConfig();

export default function () {
  const iter = __ITER;
  const mod = iter % 10;

  if (mod < 8) {
    // 80% Catalog Browsing
    windowShopperJourney(config);
  } else if (mod === 8) {
    // 10% Order Placement
    buyerJourney(config);
  } else {
    // 10% Returning Customer
    returningCustomerJourney(config);
  }
}
