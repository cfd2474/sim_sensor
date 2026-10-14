-- Sim Sensor initial schema (docs/DESIGN.md §10.2).
-- Flyway owns the schema: never edit an applied migration, add a new V<n>__*.sql instead.

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Instructors and their login sessions ---------------------------------------------------

CREATE TABLE instructors (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text        NOT NULL,
    name          text        NOT NULL,
    password_hash text        NOT NULL,
    role          text        NOT NULL CHECK (role IN ('admin', 'instructor')),
    disabled      boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX instructors_email_key ON instructors (lower(email));

CREATE TABLE instructor_sessions (
    token_hash    text        PRIMARY KEY,  -- SHA-256 of the cookie token; the token itself is never stored
    instructor_id uuid        NOT NULL REFERENCES instructors (id) ON DELETE CASCADE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL
);
CREATE INDEX instructor_sessions_instructor_idx ON instructor_sessions (instructor_id);

-- Sensor profiles (templates) and exercises ----------------------------------------------

CREATE TABLE sensor_profiles (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL,
    definition jsonb       NOT NULL,          -- shared SensorProfile JSON
    version    integer     NOT NULL DEFAULT 1,
    builtin    boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX sensor_profiles_name_key ON sensor_profiles (lower(name));

CREATE TABLE exercises (
    id                 uuid             PRIMARY KEY DEFAULT gen_random_uuid(),
    name               text             NOT NULL,
    profile_id         uuid             NOT NULL REFERENCES sensor_profiles (id),
    criteria_overrides jsonb            NOT NULL DEFAULT '{}',  -- shared CriteriaOverrides JSON
    state              text             NOT NULL DEFAULT 'draft'
                                        CHECK (state IN ('draft', 'running', 'paused', 'ended', 'archived')),
    wind_dir_deg       double precision,
    wind_speed_mps     double precision,
    o2_displacement    boolean          NOT NULL DEFAULT false,
    version            integer          NOT NULL DEFAULT 1,     -- bumped on every change pushed to devices
    created_by         uuid             REFERENCES instructors (id) ON DELETE SET NULL,
    created_at         timestamptz      NOT NULL DEFAULT now(),
    updated_at         timestamptz      NOT NULL DEFAULT now()
);

CREATE TABLE exercise_instructors (
    exercise_id   uuid NOT NULL REFERENCES exercises (id) ON DELETE CASCADE,
    instructor_id uuid NOT NULL REFERENCES instructors (id) ON DELETE CASCADE,
    PRIMARY KEY (exercise_id, instructor_id)
);
CREATE INDEX exercise_instructors_instructor_idx ON exercise_instructors (instructor_id);

-- Exercise runs (sessions) ---------------------------------------------------------------

CREATE TABLE exercise_sessions (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    exercise_id uuid        NOT NULL REFERENCES exercises (id) ON DELETE CASCADE,
    started_at  timestamptz NOT NULL DEFAULT now(),
    ended_at    timestamptz
);
CREATE INDEX exercise_sessions_exercise_idx ON exercise_sessions (exercise_id, started_at DESC);

-- Devices and enrollment -----------------------------------------------------------------

CREATE TABLE device_groups (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX device_groups_name_key ON device_groups (lower(name));

CREATE TABLE devices (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name         text        NOT NULL,
    token_hash   text        UNIQUE,            -- SHA-256 of the device bearer token
    model        text,
    app_version  text,
    group_id     uuid        REFERENCES device_groups (id) ON DELETE SET NULL,
    exercise_id  uuid        REFERENCES exercises (id) ON DELETE SET NULL,  -- at most one active exercise
    enrolled_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at   timestamptz,
    last_seen_at timestamptz
);
CREATE INDEX devices_exercise_idx ON devices (exercise_id);

CREATE TABLE enrollment_tokens (
    token_hash text        PRIMARY KEY,
    kind       text        NOT NULL CHECK (kind IN ('single', 'fleet')),  -- QR (single use) or MDM (fleet)
    label      text,
    created_by uuid        REFERENCES instructors (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    used_at    timestamptz,
    revoked_at timestamptz
);

-- Event log and telemetry ----------------------------------------------------------------

CREATE TABLE event_log (
    id          bigserial   PRIMARY KEY,
    exercise_id uuid        REFERENCES exercises (id) ON DELETE CASCADE,
    session_id  uuid        REFERENCES exercise_sessions (id) ON DELETE CASCADE,
    device_id   uuid        REFERENCES devices (id) ON DELETE SET NULL,
    actor_id    uuid        REFERENCES instructors (id) ON DELETE SET NULL,
    ts          timestamptz NOT NULL DEFAULT now(),
    type        text        NOT NULL,
    data        jsonb       NOT NULL DEFAULT '{}'
);
CREATE INDEX event_log_exercise_idx ON event_log (exercise_id, ts);

-- High-volume: a TimescaleDB hypertable, no foreign keys (ingest speed).
CREATE TABLE telemetry (
    ts          timestamptz      NOT NULL,
    device_id   uuid             NOT NULL,
    exercise_id uuid,
    session_id  uuid,
    lat         double precision,
    lon         double precision,
    accuracy_m  real,
    battery     real,
    readings    jsonb            NOT NULL,  -- {"O2": 20.9, "CO": 0, ...}
    alarms      jsonb            NOT NULL DEFAULT '[]'
);
SELECT create_hypertable('telemetry', by_range('ts', INTERVAL '1 day'));
CREATE INDEX telemetry_device_idx ON telemetry (device_id, ts DESC);
CREATE INDEX telemetry_session_idx ON telemetry (session_id, ts);

ALTER TABLE telemetry SET (timescaledb.compress, timescaledb.compress_segmentby = 'device_id');
SELECT add_compression_policy('telemetry', INTERVAL '7 days');
