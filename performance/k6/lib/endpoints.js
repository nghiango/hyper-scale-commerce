import http from 'k6/http';
import { check } from 'k6';
import {
  criticalApiDuration,
  catalogGetProductByIdDuration,
  catalogListProductsDuration,
  orderPostOrdersDuration,
  orderQueryGetOrderByIdDuration,
  orderQueryListOrdersDuration,
} from './metrics.js';

const JSON_HEADERS = {
  'Content-Type': 'application/json',
  Accept: 'application/json',
};

export function getProductById(baseUrl, id) {
  const url = `${baseUrl}/catalog/products/${id}`;
  const res = http.get(url, {
    tags: { endpoint: 'get_product_by_id' },
    headers: { Accept: 'application/json' },
  });

  criticalApiDuration.add(res.timings.duration);
  catalogGetProductByIdDuration.add(res.timings.duration);

  check(res, {
    'GET /catalog/products/:id status is 200': (r) => r.status === 200,
    'GET /catalog/products/:id has body': (r) => r.body && r.body.length > 0,
  });

  return res;
}

export function listProducts(baseUrl, page = 0, size = 20) {
  const url = `${baseUrl}/catalog/products?page=${page}&size=${size}`;
  const res = http.get(url, {
    tags: { endpoint: 'list_products' },
    headers: { Accept: 'application/json' },
  });

  criticalApiDuration.add(res.timings.duration);
  catalogListProductsDuration.add(res.timings.duration);

  check(res, {
    'GET /catalog/products status is 200': (r) => r.status === 200,
    'GET /catalog/products has items': (r) => {
      try {
        const json = JSON.parse(r.body);
        return Array.isArray(json.items || json.products || json.content);
      } catch (e) {
        return false;
      }
    },
  });

  return res;
}

export function postOrder(baseUrl, items) {
  const url = `${baseUrl}/orders`;
  const payload = JSON.stringify({ items });
  const res = http.post(url, payload, {
    tags: { endpoint: 'post_orders' },
    headers: JSON_HEADERS,
  });

  criticalApiDuration.add(res.timings.duration);
  orderPostOrdersDuration.add(res.timings.duration);

  const success = check(res, {
    'POST /orders status is 201': (r) => r.status === 201,
    'POST /orders returned order id': (r) => {
      try {
        const json = JSON.parse(r.body);
        return !!(json.id || json.orderId);
      } catch (e) {
        return false;
      }
    },
  });

  if (!success) {
    return { res, orderId: null };
  }

  try {
    const json = JSON.parse(res.body);
    const orderId = json.id || json.orderId;
    return { res, orderId };
  } catch (e) {
    return { res, orderId: null };
  }
}

export function getOrderById(baseUrl, id, customTags = {}) {
  const url = `${baseUrl}/orders/${id}`;
  const tags = Object.assign({ endpoint: 'get_order_by_id' }, customTags);
  const isPolling = !!customTags.polling;

  const params = {
    tags: tags,
    headers: { Accept: 'application/json' },
  };

  if (isPolling) {
    params.responseCallback = http.expectedStatuses(200, 404);
  }

  const res = http.get(url, params);

  if (!isPolling) {
    criticalApiDuration.add(res.timings.duration);
    orderQueryGetOrderByIdDuration.add(res.timings.duration);

    check(res, {
      'GET /orders/:id status is 200': (r) => r.status === 200,
    });
  }

  return res;
}

export function listOrders(baseUrl, page = 0, size = 20) {
  const url = `${baseUrl}/orders?page=${page}&size=${size}`;
  const res = http.get(url, {
    tags: { endpoint: 'list_orders' },
    headers: { Accept: 'application/json' },
  });

  criticalApiDuration.add(res.timings.duration);
  orderQueryListOrdersDuration.add(res.timings.duration);

  check(res, {
    'GET /orders status is 200': (r) => r.status === 200,
  });

  return res;
}
