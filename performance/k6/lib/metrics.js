import { Trend, Rate, Counter } from 'k6/metrics';

export const criticalApiDuration = new Trend('critical_api_duration', true);
export const orderProjectionLag = new Trend('order_projection_lag', true);
export const orderProjectionSuccess = new Rate('order_projection_success');
export const orderProjectionTimeouts = new Counter('order_projection_timeouts');

export const catalogGetProductByIdDuration = new Trend('catalog_get_product_by_id_duration', true);
export const catalogListProductsDuration = new Trend('catalog_list_products_duration', true);
export const orderPostOrdersDuration = new Trend('order_post_orders_duration', true);
export const orderQueryGetOrderByIdDuration = new Trend('order_query_get_order_by_id_duration', true);
export const orderQueryListOrdersDuration = new Trend('order_query_list_orders_duration', true);
