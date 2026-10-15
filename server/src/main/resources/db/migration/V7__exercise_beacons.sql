-- Phase 3 (chunk 3.1b): the beacons an exercise uses, listed on its Devices tab. Phones only
-- react to these. A beacon may belong to several exercises at once (each exercise's phones only
-- use that exercise's leaks, so shared beacons don't interfere).
CREATE TABLE exercise_beacons (
    exercise_id uuid        NOT NULL REFERENCES exercises (id) ON DELETE CASCADE,
    beacon_id   uuid        NOT NULL REFERENCES beacons (id) ON DELETE RESTRICT,
    added_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (exercise_id, beacon_id)
);
CREATE INDEX exercise_beacons_beacon_idx ON exercise_beacons (beacon_id);

-- Beacon leaks created before this table imply membership.
INSERT INTO exercise_beacons (exercise_id, beacon_id)
SELECT DISTINCT s.exercise_id, (s.definition -> 'shape' ->> 'beaconId')::uuid
  FROM gas_sources s
  JOIN beacons b ON b.id = (s.definition -> 'shape' ->> 'beaconId')::uuid
 WHERE s.definition -> 'shape' ->> 'type' = 'beacon'
ON CONFLICT DO NOTHING;
