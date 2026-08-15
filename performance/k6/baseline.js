import { getConfig } from './lib/config.js';
import { executeMixedJourney, executeReadJourney, executeWriteJourney } from './lib/journeys.js';

const STEP_DURATION = __ENV.BASELINE_STEP_DURATION || '45s';

export const options = {
  scenarios: {
    stepped_baseline: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 50 },
        { duration: STEP_DURATION, target: 50 },
        { duration: '15s', target: 100 },
        { duration: STEP_DURATION, target: 100 },
        { duration: '15s', target: 250 },
        { duration: STEP_DURATION, target: 250 },
        { duration: '15s', target: 500 },
        { duration: STEP_DURATION, target: 500 },
        { duration: '15s', target: 1000 },
        { duration: STEP_DURATION, target: 1000 },
        { duration: '15s', target: 2000 },
        { duration: STEP_DURATION, target: 2000 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: {
    critical_api_duration: ['p(95)<200'],
    http_req_failed: ['rate<0.05'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const config = getConfig();
const workload = (__ENV.WORKLOAD || 'mixed').toLowerCase();

export default function () {
  if (workload === 'read') {
    executeReadJourney(config);
  } else if (workload === 'write') {
    executeWriteJourney(config);
  } else {
    executeMixedJourney(config);
  }
}
