package com.seedshiftradio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.seedshiftradio.domain.ProviderJobStatus;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.settings.ProviderJobEntity;
import com.seedshiftradio.settings.ProviderJobRepository;
import com.seedshiftradio.settings.ProviderJobService;

@Testcontainers(disabledWithoutDocker = true)
@Tag("docker")
@SpringBootTest
class SeedShiftRadioApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("seedshift_radio")
			.withUsername("seedshift")
			.withPassword("seedshift");

	@Autowired
	ProviderJobService providerJobService;

	@Autowired
	ProviderJobRepository providerJobRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
	}

	@Test
	@Transactional
	void staleProviderJobRecoveryUsesTheDatabaseConditionAndIsIdempotent() {
		Instant now = Instant.parse("2026-07-23T00:30:00Z");
		Instant cutoff = now.minusSeconds(15 * 60);
		ProviderJobEntity stale = providerJobService.createQueuedJob(
				ProviderJobType.MUSIC_GEN, ProviderType.MUSIC, "worker-primary", null, "corr-stale");
		providerJobService.markRunning(stale.getId(), "worker-stale");
		ProviderJobEntity fresh = providerJobService.createQueuedJob(
				ProviderJobType.MUSIC_GEN, ProviderType.MUSIC, "worker-primary", null, "corr-fresh");
		providerJobService.markRunning(fresh.getId(), "worker-fresh");
		ProviderJobEntity queued = providerJobService.createQueuedJob(
				ProviderJobType.MUSIC_GEN, ProviderType.MUSIC, "worker-primary", null, "corr-queued");

		setUpdatedAt(stale.getId(), now.minusSeconds(30 * 60));
		setUpdatedAt(fresh.getId(), now.minusSeconds(5 * 60));
		setUpdatedAt(queued.getId(), now.minusSeconds(30 * 60));

		List<String> candidates = providerJobService.findStaleRunning(cutoff, 100).stream()
				.map(ProviderJobEntity::getId)
				.toList();

		assertEquals(List.of(stale.getId()), candidates);
		assertTrue(providerJobService.failIfStaleRunning(stale.getId(), cutoff, now));
		assertFalse(providerJobService.failIfStaleRunning(stale.getId(), cutoff, now));
		assertFalse(providerJobService.failIfStaleRunning(fresh.getId(), cutoff, now));
		assertFalse(providerJobService.failIfStaleRunning(queued.getId(), cutoff, now));

		ProviderJobEntity recovered = providerJobRepository.findById(stale.getId()).orElseThrow();
		assertEquals(ProviderJobStatus.FAILED, recovered.getStatus());
		assertEquals("PROVIDER_INTERRUPTED", recovered.getErrorCode());
		assertEquals(now, recovered.getEndedAt());
		assertEquals(ProviderJobStatus.RUNNING, providerJobRepository.findById(fresh.getId()).orElseThrow().getStatus());
		assertEquals(ProviderJobStatus.QUEUED, providerJobRepository.findById(queued.getId()).orElseThrow().getStatus());
	}

	private void setUpdatedAt(String providerJobId, Instant updatedAt) {
		jdbcTemplate.update("update provider_job set updated_at = ? where id = ?", Timestamp.from(updatedAt), providerJobId);
	}

}
