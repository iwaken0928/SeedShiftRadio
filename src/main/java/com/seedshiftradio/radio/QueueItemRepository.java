package com.seedshiftradio.radio;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.seedshiftradio.domain.QueueItemStatus;

public interface QueueItemRepository extends JpaRepository<QueueItemEntity, String> {

	List<QueueItemEntity> findBySessionIdOrderBySequenceNoAsc(String sessionId);

	List<QueueItemEntity> findByProgramBlockIdOrderBySequenceNoAsc(String programBlockId);

	List<QueueItemEntity> findByProgramBlockIdInOrderByProgramBlockIdAscSequenceNoAsc(List<String> programBlockIds);

	List<QueueItemEntity> findByAssetIdIn(List<String> assetIds);

	boolean existsByAssetId(String assetId);

	long countBySessionIdAndStatus(String sessionId, QueueItemStatus status);

	@Query("""
			SELECT COALESCE(SUM(item.durationMs), 0)
			FROM QueueItemEntity item
			WHERE item.sessionId = :sessionId
				AND item.status = :status
			""")
	long sumDurationMsBySessionIdAndStatus(
			@Param("sessionId") String sessionId,
			@Param("status") QueueItemStatus status);

	Optional<QueueItemEntity> findTopBySessionIdAndStatusOrderBySequenceNoAsc(String sessionId, QueueItemStatus status);

	long countByProgramBlockIdAndContentOrigin(String programBlockId, String contentOrigin);
}
