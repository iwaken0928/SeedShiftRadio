package com.seedshiftradio.management;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PreGenerationRequestRepository extends JpaRepository<PreGenerationRequestEntity, String> {

	List<PreGenerationRequestEntity> findTop10ByOrderByRequestedAtDesc();

	Optional<PreGenerationRequestEntity> findFirstByStationIdOrderByRequestedAtDesc(String stationId);
}
