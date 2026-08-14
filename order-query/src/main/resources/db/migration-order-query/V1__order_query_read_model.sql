CREATE SCHEMA IF NOT EXISTS order_query;

CREATE TABLE order_query.order_read_model (
    order_id BIGINT PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    items JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
