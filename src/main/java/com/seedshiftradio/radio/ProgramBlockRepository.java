package com.seedshiftradio.radio;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProgramBlockRepository extends JpaRepository<ProgramBlockEntity, String> {

	List<ProgramBlockEntity> findBySessionIdOrderByStartedAtAsc(String sessionId);

	Optional<ProgramBlockEntity> findTopBySessionIdOrderByStartedAtDesc(String sessionId);

	long countByStationId(String stationId);

	Optional<ProgramBlockEntity> findTopByStationIdOrderByStartedAtDesc(String stationId);

	List<ProgramBlockEntity> findTop100ByStationIdOrderByStartedAtDesc(String stationId);

	@Query(value = """
			SELECT COUNT(*)
			FROM program_block pb
			JOIN playout_session ps ON ps.id = pb.session_id
			WHERE pb.station_id = :stationId
				AND ps.purpose = 'PRE_GENERATION'
			""", nativeQuery = true)
	long countPreGeneratedByStationId(@Param("stationId") String stationId);
}
