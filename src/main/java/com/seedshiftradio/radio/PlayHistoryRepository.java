package com.seedshiftradio.radio;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.seedshiftradio.domain.PlayHistoryResultStatus;

public interface PlayHistoryRepository extends JpaRepository<PlayHistoryEntity, String>, JpaSpecificationExecutor<PlayHistoryEntity> {

	List<PlayHistoryEntity> findBySessionIdOrderByPlayedAtDesc(String sessionId);

	List<PlayHistoryEntity> findByLetterIdOrderByPlayedAtDesc(String letterId);

	List<PlayHistoryEntity> findByLetterIdInOrderByPlayedAtDesc(Collection<String> letterIds);

	long countByResultStatus(PlayHistoryResultStatus resultStatus);

	long countByStationIdAndResultStatus(String stationId, PlayHistoryResultStatus resultStatus);

	long countByResultStatusAndContentOrigin(PlayHistoryResultStatus resultStatus, String contentOrigin);

	long countByStationIdAndResultStatusAndContentOrigin(
			String stationId,
			PlayHistoryResultStatus resultStatus,
			String contentOrigin);
}
