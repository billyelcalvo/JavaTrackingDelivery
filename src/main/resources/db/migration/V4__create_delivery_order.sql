CREATE TABLE delivery_order (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    pickup        GEOGRAPHY(POINT, 4326) NOT NULL,
    dropoff       GEOGRAPHY(POINT, 4326) NOT NULL,
    courier_id    UUID REFERENCES courier (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_at   TIMESTAMPTZ,
    picked_up_at  TIMESTAMPTZ,
    delivered_at  TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ,
    CONSTRAINT delivery_order_status_chk
        CHECK (status IN ('PENDING', 'ASSIGNED', 'PICKED_UP', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT delivery_order_courier_chk
        CHECK (status IN ('PENDING', 'CANCELLED') OR courier_id IS NOT NULL)
);

-- The retry job scans pending orders oldest first
CREATE INDEX delivery_order_pending_idx ON delivery_order (created_at) WHERE status = 'PENDING';

-- A courier can only carry one active order at a time; the database enforces it
-- even if the application logic had a bug.
CREATE UNIQUE INDEX delivery_order_one_active_per_courier
    ON delivery_order (courier_id) WHERE status IN ('ASSIGNED', 'PICKED_UP');
