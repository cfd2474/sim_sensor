-- Chunk 1.2: enrollment token ids, per-device reading overrides, device lookups.

-- Tokens are listed and revoked by id; the token itself is never stored or shown again.
ALTER TABLE enrollment_tokens ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE enrollment_tokens ADD CONSTRAINT enrollment_tokens_id_key UNIQUE (id);

-- Active instructor overrides of a device's readings (docs/DESIGN.md §8.3).
-- channel_id '*' applies to every channel without its own override.
-- Released overrides are deleted (and logged in event_log).
CREATE TABLE device_overrides (
    device_id    uuid             NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    channel_id   text             NOT NULL,
    exercise_id  uuid             NOT NULL REFERENCES exercises (id) ON DELETE CASCADE,
    mode         text             NOT NULL CHECK (mode IN ('fixed', 'ramp', 'offset', 'fault')),
    value        double precision,
    ramp_seconds double precision,
    created_by   uuid             REFERENCES instructors (id) ON DELETE SET NULL,
    created_at   timestamptz      NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, channel_id)
);

-- Raised whenever a device's override list changes, so the device can drop stale updates.
ALTER TABLE devices ADD COLUMN overrides_version integer NOT NULL DEFAULT 0;

CREATE INDEX devices_group_idx ON devices (group_id);
