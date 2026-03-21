package com.seedshiftradio.station;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StationRepository extends JpaRepository<StationEntity, String> {
}
