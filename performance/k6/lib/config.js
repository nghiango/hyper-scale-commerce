/**
 * Performance Harness Configuration & Safety Guards
 */

const ALLOWED_TARGET_HOSTS = [
  'app:8080',
  'order-query:8081',
  'localhost:8080',
  'localhost:8081',
  '127.0.0.1:8080',
  '127.0.0.1:8081',
];

function validateTargetUrl(url, label) {
  if (!url) {
    throw new Error(`Missing required target URL for ${label}`);
  }

  // Check against allow-listed local hosts unless explicitly overridden
  const allowRemote = __ENV.ALLOW_REMOTE_TARGET === 'true';
  if (!allowRemote) {
    const isAllowed = ALLOWED_TARGET_HOSTS.some((host) => url.includes(host));
    if (!isAllowed) {
      throw new Error(
        `Safety Guard: Target URL '${url}' for ${label} is not in the approved local allowlist (${ALLOWED_TARGET_HOSTS.join(', ')}). Set ALLOW_REMOTE_TARGET=true only with explicit authorization.`
      );
    }
  }
}

export function getConfig() {
  const appBaseUrl = (__ENV.APP_BASE_URL || 'http://app:8080').replace(/\/+$/, '');
  const orderQueryBaseUrl = (__ENV.ORDER_QUERY_BASE_URL || 'http://order-query:8081').replace(/\/+$/, '');

  validateTargetUrl(appBaseUrl, 'APP_BASE_URL');
  validateTargetUrl(orderQueryBaseUrl, 'ORDER_QUERY_BASE_URL');

  const prngSeed = parseInt(__ENV.PRNG_SEED || '42', 10);
  const catalogSize = parseInt(__ENV.CATALOG_SIZE || '1000', 10);
  const maxVUs = parseInt(__ENV.MAX_VUS || '12000', 10);

  return {
    appBaseUrl,
    orderQueryBaseUrl,
    prngSeed,
    catalogSize,
    maxVUs,
  };
}
