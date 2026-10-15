-- Monitors (chunk 2.7): each phone in an exercise imitates a monitor (a row of sensor_profiles).
-- NULL means the exercise's default monitor (exercises.profile_id). Moving a phone to another
-- exercise clears it. Deleting a monitor in use is refused by the server; SET NULL is a backstop.
ALTER TABLE devices ADD COLUMN monitor_id uuid REFERENCES sensor_profiles (id) ON DELETE SET NULL;
CREATE INDEX devices_monitor_id_idx ON devices (monitor_id) WHERE monitor_id IS NOT NULL;

-- Per-exercise alarm criteria are retired: alarm levels live on the monitor. The column stays
-- (no data is destroyed) but is no longer read or written.
COMMENT ON COLUMN exercises.criteria_overrides IS 'Retired in v0.11.0 (monitors); not read.';
