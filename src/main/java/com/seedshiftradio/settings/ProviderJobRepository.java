package com.seedshiftradio.settings;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.seedshiftradio.domain.ProviderJobStatus;

public interface ProviderJobRepository extends JpaRepository<ProviderJobEntity, String> {

	List<ProviderJobEntity> findTop10ByStatusOrderByUpdatedAtDesc(ProviderJobStatus status);

	List<ProviderJobEntity> findTop20ByOrderByUpdatedAtDesc();

	List<ProviderJobEntity> findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
			ProviderJobStatus status,
			Instant cutoff,
			Pageable pageable);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ProviderJobEntity job
			   set job.status = :failedStatus,
			       job.errorCode = :errorCode,
			       job.endedAt = :endedAt,
			       job.updatedAt = :endedAt
			 where job.id = :id
			   and job.status = :runningStatus
			   and job.updatedAt < :cutoff
			""")
	int failIfStaleRunning(
			@Param("id") String id,
			@Param("runningStatus") ProviderJobStatus runningStatus,
			@Param("failedStatus") ProviderJobStatus failedStatus,
			@Param("errorCode") String errorCode,
			@Param("cutoff") Instant cutoff,
			@Param("endedAt") Instant endedAt);
}
