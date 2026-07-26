package com.seedshiftradio.monitor;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationalEventRepository extends JpaRepository<OperationalEventEntity, String> {

	List<OperationalEventEntity> findAllByOrderByOccurredAtDesc(Pageable pageable);

	long deleteByOccurredAtBefore(Instant cutoff);
}
