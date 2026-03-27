ALTER TABLE station
    ADD CONSTRAINT uq_station_frequency_mhz UNIQUE (frequency_mhz);
