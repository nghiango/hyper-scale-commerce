import { getConfig } from './lib/config.js';
import { executeMixedJourney } from './lib/journeys.js';

const RATE_1X = parseInt(__ENV.SPIKE_1X_RATE || '500', 10);
const RATE_5X = parseInt(__ENV.SPIKE_5X_RATE || '2500', 10);
const STAGE1_DUR = __ENV.SPIKE_STAGE1 || '1m';
const RAMP_UP_DUR = __ENV.SPIKE_RAMP_UP || '15s';
const BURST_DUR = __ENV.SPIKE_BURST || '1m';
const RAMP_DOWN_DUR = __ENV.SPIKE_RAMP_DOWN || '15s';
const RECOVERY_DUR = __ENV.SPIKE_RECOVERY || '2m';

export const options = {
  scenarios: {
    spike_5x: {
      executor: 'ramping-arrival-rate',
      startRate: RATE_1X,
      timeUnit: '1s',
      preAllocatedVUs: 1000,
      maxVUs: 10000,
      stages: [
        { duration: STAGE1_DUR, target: RATE_1X },       // 1x baseline steady state
        { duration: RAMP_UP_DUR, target: RATE_5X },      // 5x spike ramp
        { duration: BURST_DUR, target: RATE_5X },        // 5x spike burst
        { duration: RAMP_DOWN_DUR, target: RATE_1X },    // ramp down to 1x
        { duration: RECOVERY_DUR, target: RATE_1X },     // 1x recovery observation window
      ],
      gracefulStop: '30s',
    },
  },
  thresholds: {
    critical_api_duration: ['p(95)<200'],
    'critical_api_duration{endpoint:get_product_by_id}': ['p(95)<200'],
    'critical_api_duration{endpoint:list_products}': ['p(95)<200'],
    'critical_api_duration{endpoint:post_orders}': ['p(95)<200'],
    'critical_api_duration{endpoint:get_order_by_id}': ['p(95)<200'],
    'critical_api_duration{endpoint:list_orders}': ['p(95)<200'],
    http_req_failed: ['rate<0.01'], // 99% availability during 5x spike overload
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const config = getConfig();

export default function () {
  executeMixedJourney(config);
}
