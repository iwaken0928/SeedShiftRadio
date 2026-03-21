package com.seedshiftradio.programming;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StationProgrammingPolicyRepository extends JpaRepository<StationProgrammingPolicyEntity, String> {

	Optional<StationProgrammingPolicyEntity> findByStationId(String stationId);
}
