CREATE SCHEMA IF NOT EXISTS "order";

CREATE TABLE "order".outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_events_unpublished
    ON "order".outbox_events (created_at)
    WHERE published_at IS NULL;
