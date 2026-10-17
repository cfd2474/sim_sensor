-- Chunk 4.3: one entry per phone. SHA-256 of the phone's ANDROID_ID; a phone that enrolls again
-- gets its existing entry back instead of a new one. NULL for entries enrolled before v0.23.0
-- until the phone next connects.
ALTER TABLE devices ADD COLUMN hardware_id text;
CREATE UNIQUE INDEX devices_hardware_id_key ON devices (hardware_id) WHERE hardware_id IS NOT NULL;
