-- Latest known position, one row per courier. Kept apart from "courier" on purpose:
-- this table gets hammered by the location flusher, and the assignment query locks
-- rows in "courier". Splitting them means a location write never blocks (or gets
-- skipped by) an assignment.
CREATE TABLE courier_location (
    courier_id   UUID PRIMARY KEY REFERENCES courier (id) ON DELETE CASCADE,
    location     GEOGRAPHY(POINT, 4326) NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX courier_location_gix ON courier_location USING GIST (location);

-- Append-only trail of every accepted ping. BRIN is tiny and works well here because
-- rows arrive roughly in time order.
CREATE TABLE courier_location_history (
    id           BIGSERIAL PRIMARY KEY,
    courier_id   UUID NOT NULL,
    location     GEOGRAPHY(POINT, 4326) NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX courier_location_history_recorded_brin
    ON courier_location_history USING BRIN (recorded_at);
CREATE INDEX courier_location_history_courier_idx
    ON courier_location_history (courier_id, recorded_at DESC);
