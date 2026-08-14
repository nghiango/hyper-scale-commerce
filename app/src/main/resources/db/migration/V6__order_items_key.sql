-- Spring Data JDBC ordered aggregate: preserves order_items line-item order.
ALTER TABLE "order".order_items ADD COLUMN orders_key INT;
