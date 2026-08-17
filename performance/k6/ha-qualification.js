import { getConfig } from './lib/config.js';
import { executeMixedJourney } from './lib/journeys.js';

const TARGET_VUS = parseInt(__ENV.HA_TARGET_VUS || '500', 10);
const RAMP_UP = __ENV.HA_RAMP_UP || '15s';
const STEADY_STATE = __ENV.HA_STEADY_STATE || '45s';
const RAMP_DOWN = __ENV.HA_RAMP_DOWN || '15s';

export const options = {
  scenarios: {
    ha_qualification: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP_UP, target: TARGET_VUS },       // Ramp-up
        { duration: STEADY_STATE, target: TARGET_VUS },  // Steady state hold
        { duration: RAMP_DOWN, target: 0 },              // Ramp-down
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: {
    critical_api_duration: ['p(95)<200'],
    'critical_api_duration{endpoint:get_product_by_id}': ['p(95)<200'],
    'critical_api_duration{endpoint:list_products}': ['p(95)<200'],
    'critical_api_duration{endpoint:post_orders}': ['p(95)<200'],
    'critical_api_duration{endpoint:get_order_by_id}': ['p(95)<200'],
    'critical_api_duration{endpoint:list_orders}': ['p(95)<200'],
    http_req_failed: ['rate<0.001'], // 99.9% success on no-fault run
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const config = getConfig();

export default function () {
  executeMixedJourney(config);
}
