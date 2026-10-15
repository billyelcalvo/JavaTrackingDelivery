CREATE TABLE courier (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(120) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'OFFLINE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT courier_status_chk CHECK (status IN ('OFFLINE', 'AVAILABLE', 'BUSY'))
);
