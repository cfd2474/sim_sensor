-- Phase 2: virtual leaks, the exercise clock, and system settings.

-- Virtual leaks placed by instructors (docs/DESIGN.md §8.1). The definition is the shared
-- GasSource JSON (shape, gases, falloff, timing, enabled); its id and manual activation time
-- live in their own columns.
CREATE TABLE gas_sources (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    exercise_id    uuid        NOT NULL REFERENCES exercises (id) ON DELETE CASCADE,
    definition     jsonb       NOT NULL,
    activated_at_s double precision,          -- exercise time a MANUAL source was activated
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX gas_sources_exercise_idx ON gas_sources (exercise_id, created_at);

-- Exercise clock: elapsed run time, frozen while paused.
--   elapsed now = clock_elapsed_s + (now() - clock_resumed_at)   while running
--               = clock_elapsed_s                                 otherwise
ALTER TABLE exercises ADD COLUMN clock_elapsed_s double precision NOT NULL DEFAULT 0;
ALTER TABLE exercises ADD COLUMN clock_resumed_at timestamptz;

-- System-wide settings (key -> JSON value), e.g. display units.
CREATE TABLE system_settings (
    key        text        PRIMARY KEY,
    value      jsonb       NOT NULL,
    updated_by uuid        REFERENCES instructors (id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
