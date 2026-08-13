CREATE SCHEMA IF NOT EXISTS catalog;

CREATE TABLE IF NOT EXISTS catalog.products (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price INTEGER NOT NULL CHECK (price >= 0),
    availability VARCHAR(50) NOT NULL DEFAULT 'IN_STOCK' CHECK (availability IN ('IN_STOCK', 'OUT_OF_STOCK')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_products_sku ON catalog.products (sku);
CREATE INDEX IF NOT EXISTS idx_products_name ON catalog.products (name);
