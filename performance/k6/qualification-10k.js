import { getConfig } from './lib/config.js';
import { executeMixedJourney } from './lib/journeys.js';

const TARGET_VUS = parseInt(__ENV.QUAL_TARGET_VUS || '10000', 10);
const RAMP_UP = __ENV.QUAL_RAMP_UP || '30s';
const STEADY_STATE = __ENV.QUAL_STEADY_STATE || '15m';
const RAMP_DOWN = __ENV.QUAL_RAMP_DOWN || '30s';

export const options = {
  scenarios: {
    qualification_10k: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP_UP, target: TARGET_VUS },       // Ramp-up
        { duration: STEADY_STATE, target: TARGET_VUS },  // Steady state
        { duration: RAMP_DOWN, target: 0 },              // Ramp-down
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    critical_api_duration: ['p(95)<200'],
    'critical_api_duration{endpoint:get_product_by_id}': ['p(95)<200'],
    'critical_api_duration{endpoint:list_products}': ['p(95)<200'],
    'critical_api_duration{endpoint:post_orders}': ['p(95)<200'],
    'critical_api_duration{endpoint:get_order_by_id}': ['p(95)<200'],
    'critical_api_duration{endpoint:list_orders}': ['p(95)<200'],
    http_req_failed: ['rate<0.001'], // 99.9% availability
    dropped_iterations: ['count<1'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const config = getConfig();

export default function () {
  executeMixedJourney(config);
}

export function handleSummary(data) {
  return {
    stdout: `PHASE18_SUMMARY_JSON=${JSON.stringify(data)}\n`,
  };
}
