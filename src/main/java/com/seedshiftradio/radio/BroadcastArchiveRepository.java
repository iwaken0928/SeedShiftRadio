package com.seedshiftradio.radio;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.seedshiftradio.domain.SegmentType;

public interface BroadcastArchiveRepository extends JpaRepository<BroadcastArchiveEntity, String> {

	boolean existsBySourcePlayHistoryId(String sourcePlayHistoryId);

	Optional<BroadcastArchiveEntity> findBySourcePlayHistoryId(String sourcePlayHistoryId);

	long countByStationId(String stationId);

	@Query("""
			SELECT COUNT(archive)
			FROM BroadcastArchiveEntity archive
			WHERE archive.archiveStatus = 'ELIGIBLE'
				AND archive.eligibleFrom <= :now
				AND (archive.expiresAt IS NULL OR archive.expiresAt > :now)
			""")
	long countEligibleArchives(@Param("now") Instant now);

	@Query("""
			SELECT COUNT(archive)
			FROM BroadcastArchiveEntity archive
			WHERE archive.stationId = :stationId
				AND archive.archiveStatus = 'ELIGIBLE'
				AND archive.eligibleFrom <= :now
				AND (archive.expiresAt IS NULL OR archive.expiresAt > :now)
			""")
	long countEligibleArchivesByStationId(
			@Param("stationId") String stationId,
			@Param("now") Instant now);

	@Query("""
			SELECT archive
			FROM BroadcastArchiveEntity archive
			WHERE archive.stationId = :stationId
				AND archive.segmentType = :segmentType
				AND archive.archiveStatus = 'ELIGIBLE'
				AND archive.eligibleFrom <= :now
				AND (archive.expiresAt IS NULL OR archive.expiresAt > :now)
				AND (archive.lastReplayedAt IS NULL OR archive.lastReplayedAt <= :cooldownCutoff)
			ORDER BY archive.replayCount ASC, archive.replayWeight DESC, archive.lastReplayedAt ASC NULLS FIRST, archive.createdAt ASC
			""")
	List<BroadcastArchiveEntity> findReplayCandidates(
			@Param("stationId") String stationId,
			@Param("segmentType") SegmentType segmentType,
			@Param("now") Instant now,
			@Param("cooldownCutoff") Instant cooldownCutoff);
}
