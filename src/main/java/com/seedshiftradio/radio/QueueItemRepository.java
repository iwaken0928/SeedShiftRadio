package com.seedshiftradio.radio;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.seedshiftradio.domain.QueueItemStatus;

public interface QueueItemRepository extends JpaRepository<QueueItemEntity, String> {

	List<QueueItemEntity> findBySessionIdOrderBySequenceNoAsc(String sessionId);

	long countBySessionIdAndStatus(String sessionId, QueueItemStatus status);

	Optional<QueueItemEntity> findTopBySessionIdAndStatusOrderBySequenceNoAsc(String sessionId, QueueItemStatus status);
}
