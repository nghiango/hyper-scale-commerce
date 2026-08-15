import { sleep, check } from 'k6';
import { getProductById, listProducts, postOrder, getOrderById, listOrders } from './endpoints.js';
import {
  orderProjectionLag,
  orderProjectionSuccess,
  orderProjectionTimeouts,
} from './metrics.js';

// Linear Congruential Generator for reproducible pseudo-random numbers
function pseudoRandom(seed) {
  let s = seed % 2147483647;
  if (s <= 0) s += 2147483646;
  return function () {
    s = (s * 16807) % 2147483647;
    return (s - 1) / 2147483646;
  };
}

let randGen = null;

function getRand(config) {
  if (!randGen) {
    randGen = pseudoRandom(config.prngSeed || 42);
  }
  return randGen();
}

function randomBetween(min, max, config) {
  return min + getRand(config) * (max - min);
}

function getRandomSku(config) {
  const index = Math.floor(getRand(config) * config.catalogSize) + 1;
  return `PERF-SKU-${index.toString().padStart(5, '0')}`;
}

function getRandomProductId(config) {
  return Math.floor(getRand(config) * config.catalogSize) + 1;
}

export function windowShopperJourney(config) {
  const page = Math.floor(getRand(config) * 10);
  listProducts(config.appBaseUrl, page, 20);

  sleep(randomBetween(1.0, 3.0, config));

  const productId = getRandomProductId(config);
  getProductById(config.appBaseUrl, productId);

  sleep(randomBetween(1.0, 2.0, config));
}

export function buyerJourney(config) {
  const sku = getRandomSku(config);
  const startTime = Date.now();

  const { res, orderId } = postOrder(config.appBaseUrl, [{ sku, quantity: 1 }]);

  if (orderId) {
    // 500ms initial async propagation think time before checking projection
    sleep(0.5);

    // Poll order-query for asynchronous read-model projection visibility
    const deadline = Date.now() + 10000; // 10 second hard SLA timeout
    let visible = false;
    let attempts = 0;

    while (Date.now() < deadline) {
      attempts++;
      const queryRes = getOrderById(config.orderQueryBaseUrl, orderId, { polling: 'true' });

      if (queryRes.status === 200) {
        visible = true;
        const lagSeconds = (Date.now() - startTime) / 1000.0;
        orderProjectionLag.add(lagSeconds);
        orderProjectionSuccess.add(1);
        break;
      }

      sleep(0.5); // 500ms poll interval
    }

    if (!visible) {
      orderProjectionSuccess.add(0);
      orderProjectionTimeouts.add(1);
    }
  }

  sleep(randomBetween(1.5, 3.5, config));
}

export function returningCustomerJourney(config) {
  const page = Math.floor(getRand(config) * 5);
  const listRes = listOrders(config.orderQueryBaseUrl, page, 20);

  sleep(randomBetween(1.5, 3.0, config));

  // If orders exist in the response, pick the first one to query by ID
  let orderIdToFetch = null;
  try {
    const body = JSON.parse(listRes.body);
    const orders = body.items || body.orders || body.content || [];
    if (orders.length > 0 && orders[0].id) {
      orderIdToFetch = orders[0].id;
    }
  } catch (e) {
    // ignore parse error
  }

  if (orderIdToFetch) {
    getOrderById(config.orderQueryBaseUrl, orderIdToFetch);
  }

  sleep(randomBetween(1.0, 2.0, config));
}

/**
 * Executes a representative mixed journey (80% Catalog, 10% Buyer, 10% Order Query)
 */
export function executeMixedJourney(config) {
  const roll = getRand(config);

  if (roll < 0.80) {
    windowShopperJourney(config);
  } else if (roll < 0.90) {
    buyerJourney(config);
  } else {
    returningCustomerJourney(config);
  }
}

/**
 * Executes a 100% read-only journey (85% Catalog, 15% Order Query)
 */
export function executeReadJourney(config) {
  const roll = getRand(config);
  if (roll < 0.85) {
    windowShopperJourney(config);
  } else {
    returningCustomerJourney(config);
  }
}

/**
 * Executes a 100% write-heavy buyer journey (POST /orders + async projection poll)
 */
export function executeWriteJourney(config) {
  buyerJourney(config);
}
