package com.seedshiftradio.radio;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayHistoryRepository extends JpaRepository<PlayHistoryEntity, String> {

	List<PlayHistoryEntity> findBySessionIdOrderByPlayedAtDesc(String sessionId);
}
