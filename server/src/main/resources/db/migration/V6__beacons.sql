-- Phase 3: beacon inventory (docs/DESIGN.md §8.2). Beacon leaks are gas_sources whose shape is
-- {"type":"beacon","beaconId":...}; a beacon serves one running exercise at a time (checked by
-- the server when a run starts or a beacon leak is added).
CREATE TABLE beacons (
    id             uuid             PRIMARY KEY DEFAULT gen_random_uuid(),
    label          text             NOT NULL,
    uuid           text             NOT NULL,          -- iBeacon proximity UUID, lower case with dashes
    major          integer          NOT NULL CHECK (major BETWEEN 0 AND 65535),
    minor          integer          NOT NULL CHECK (minor BETWEEN 0 AND 65535),
    tx_power_1m    double precision,                   -- calibrated RSSI at 1 m; NULL = the beacon's own
    path_loss_n    double precision NOT NULL DEFAULT 2.5,
    notes          text             NOT NULL DEFAULT '',
    created_at     timestamptz      NOT NULL DEFAULT now(),
    updated_at     timestamptz      NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX beacons_identity_key ON beacons (uuid, major, minor);
