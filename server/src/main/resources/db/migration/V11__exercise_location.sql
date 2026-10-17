-- Chunk 4.5: where an exercise takes place (address chosen when creating it). The console's map
-- starts there; phones don't use it.
ALTER TABLE exercises ADD COLUMN location_label text;
ALTER TABLE exercises ADD COLUMN location_lat double precision;
ALTER TABLE exercises ADD COLUMN location_lon double precision;
