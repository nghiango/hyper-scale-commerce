ALTER TABLE order_query.order_read_model
ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
