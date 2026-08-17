import { getConfig } from './lib/config.js';
import { executeMixedJourney } from './lib/journeys.js';

const RATE_1X = parseInt(__ENV.SPIKE_1X_RATE || '500', 10);
const RATE_5X = parseInt(__ENV.SPIKE_5X_RATE || '2500', 10);

const arrivalScenario = (rate, duration, startTime) => ({
  executor: 'constant-arrival-rate',
  exec: 'mixedJourney',
  rate,
  timeUnit: '1s',
  duration,
  startTime,
  preAllocatedVUs: 2000,
  maxVUs: 10000,
  gracefulStop: '30s',
});

export const options = {
  scenarios: {
    spike_baseline: arrivalScenario(RATE_1X, '2m', '0s'),
    spike_burst: {
      executor: 'ramping-arrival-rate',
      exec: 'mixedJourney',
      startTime: '2m',
      startRate: RATE_1X,
      timeUnit: '1s',
      preAllocatedVUs: 2000,
      maxVUs: 10000,
      stages: [
        { duration: '30s', target: RATE_5X },
        { duration: '1m', target: RATE_5X },
        { duration: '30s', target: RATE_1X },
      ],
      gracefulStop: '30s',
    },
    spike_recovery_observation: arrivalScenario(RATE_1X, '4m', '4m'),
    // The last minute is a separate gate: it proves recovery by the end of the
    // allowed five-minute window instead of hiding recovery in an aggregate.
    spike_recovery_gate: arrivalScenario(RATE_1X, '1m', '8m'),
  },
  thresholds: {
    critical_api_duration: ['p(95)<200'],
    'critical_api_duration{scenario:spike_baseline}': ['p(95)<200'],
    'critical_api_duration{scenario:spike_recovery_gate}': ['p(95)<200'],
    http_req_failed: ['rate<0.001'],
    'http_req_failed{scenario:spike_baseline}': ['rate<0.001'],
    'http_req_failed{scenario:spike_recovery_gate}': ['rate<0.001'],
    dropped_iterations: ['count<1'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const config = getConfig();

export function mixedJourney() {
  executeMixedJourney(config);
}

export function handleSummary(data) {
  return {
    stdout: `PHASE18_SUMMARY_JSON=${JSON.stringify(data)}\n`,
  };
}
