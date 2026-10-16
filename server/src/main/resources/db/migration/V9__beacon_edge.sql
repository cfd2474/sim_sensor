-- Chunk 3.4: gas edge per beacon (dBm). Below it a phone gets no reading from the beacon's
-- leaks, so signal through a wall doesn't count. NULL = no edge (leak reach only).
ALTER TABLE beacons ADD COLUMN edge_rssi double precision;
