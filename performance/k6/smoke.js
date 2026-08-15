import { getConfig } from './lib/config.js';
import { windowShopperJourney, buyerJourney, returningCustomerJourney } from './lib/journeys.js';

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: 10,
      duration: '30s',
    },
  },
  thresholds: {
    critical_api_duration: ['p(90)<200', 'p(95)<500'],
    http_req_failed: ['rate<0.01'],
    order_projection_success: ['rate>0.90'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const config = getConfig();

export default function () {
  const iter = __ITER;
  const mod = iter % 3;

  if (mod === 0) {
    windowShopperJourney(config);
  } else if (mod === 1) {
    buyerJourney(config);
  } else {
    returningCustomerJourney(config);
  }
}
