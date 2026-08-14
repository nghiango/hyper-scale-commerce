CREATE SCHEMA IF NOT EXISTS inventory;

CREATE TABLE inventory.reservations (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    sku VARCHAR(100) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    status VARCHAR(50) NOT NULL,
    event_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reservations_event_sku UNIQUE (event_id, sku)
);
