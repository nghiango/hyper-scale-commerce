CREATE TABLE "order".order_read_model (
    order_id BIGINT PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    items JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
