CREATE SCHEMA IF NOT EXISTS "order";

CREATE TABLE "order".orders (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE "order".order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES "order".orders (id),
    sku VARCHAR(100) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0)
);
