package com.seedshiftradio.radio;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PlayHistoryRepository extends JpaRepository<PlayHistoryEntity, String>, JpaSpecificationExecutor<PlayHistoryEntity> {

	List<PlayHistoryEntity> findBySessionIdOrderByPlayedAtDesc(String sessionId);

	List<PlayHistoryEntity> findByLetterIdOrderByPlayedAtDesc(String letterId);

	List<PlayHistoryEntity> findByLetterIdInOrderByPlayedAtDesc(Collection<String> letterIds);
}
