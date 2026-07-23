package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.ProviderJobStatus;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.stream.StreamEventService;

@ExtendWith(MockitoExtension.class)
class ProviderJobServiceTests {

	@Mock
	ProviderJobRepository providerJobRepository;

	@Mock
	StreamEventService streamEventService;

	ProviderJobService providerJobService;

	@BeforeEach
	void setUp() {
		providerJobService = new ProviderJobService(providerJobRepository, streamEventService);
	}

	@Test
	void markFailedNormalizesUnknownExternalCodeBeforePersistenceAndSse() {
		ProviderJobEntity entity = new ProviderJobEntity();
		entity.setId("provider-job-001");
		entity.setJobType(ProviderJobType.MUSIC_GEN);
		entity.setProviderType(ProviderType.MUSIC);
		entity.setProviderKey("worker-primary");
		entity.setQueueItemId("queue-001");
		entity.setStatus(ProviderJobStatus.RUNNING);
		entity.setCorrelationId("corr-001");
		when(providerJobRepository.findById("provider-job-001")).thenReturn(Optional.of(entity));
		when(providerJobRepository.save(any(ProviderJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

		ProviderJobEntity failed = providerJobService.markFailed("provider-job-001", "WORKER_PRIVATE_ERROR");

		assertEquals(ProviderJobStatus.FAILED, failed.getStatus());
		assertEquals("PROVIDER_BAD_RESPONSE", failed.getErrorCode());
		verify(providerJobRepository).save(entity);
		ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
		verify(streamEventService).publish(org.mockito.ArgumentMatchers.eq("provider.job.failed"), payloadCaptor.capture());
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
		assertEquals("PROVIDER_BAD_RESPONSE", payload.get("errorCode"));
		assertEquals(false, payload.containsValue("WORKER_PRIVATE_ERROR"));
	}

	@Test
	void findStaleRunningLimitsOldestRunningJobs() {
		Instant cutoff = Instant.parse("2026-07-23T00:00:00Z");
		ProviderJobEntity stale = job("provider-job-stale", ProviderJobStatus.RUNNING);
		when(providerJobRepository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
				eq(ProviderJobStatus.RUNNING),
				eq(cutoff),
				any(Pageable.class))).thenReturn(List.of(stale));

		List<ProviderJobEntity> result = providerJobService.findStaleRunning(cutoff, 25);

		assertEquals(List.of(stale), result);
		ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
		verify(providerJobRepository).findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
				eq(ProviderJobStatus.RUNNING),
				eq(cutoff),
				pageableCaptor.capture());
		assertEquals(25, pageableCaptor.getValue().getPageSize());
		assertEquals(0, pageableCaptor.getValue().getPageNumber());
	}

	@Test
	void failIfStaleRunningPublishesOneSafeInterruptedFailure() {
		Instant cutoff = Instant.parse("2026-07-23T00:00:00Z");
		Instant failedAt = Instant.parse("2026-07-23T00:15:00Z");
		ProviderJobEntity failed = job("provider-job-stale", ProviderJobStatus.FAILED);
		failed.setErrorCode("PROVIDER_INTERRUPTED");
		failed.setEndedAt(failedAt);
		when(providerJobRepository.failIfStaleRunning(
				"provider-job-stale",
				ProviderJobStatus.RUNNING,
				ProviderJobStatus.FAILED,
				"PROVIDER_INTERRUPTED",
				cutoff,
				failedAt)).thenReturn(1);
		when(providerJobRepository.findById("provider-job-stale")).thenReturn(Optional.of(failed));

		boolean recovered = providerJobService.failIfStaleRunning("provider-job-stale", cutoff, failedAt);

		assertTrue(recovered);
		ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
		verify(streamEventService).publish(eq("provider.job.failed"), payloadCaptor.capture());
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
		assertEquals("provider-job-stale", payload.get("providerJobId"));
		assertEquals("FAILED", payload.get("status"));
		assertEquals("PROVIDER_INTERRUPTED", payload.get("errorCode"));
		assertEquals("corr-001", payload.get("correlationId"));
		assertFalse(payload.containsKey("prompt"));
		assertFalse(payload.containsKey("lyrics"));
		assertFalse(payload.containsKey("apiKey"));
		assertFalse(payload.containsKey("adminToken"));
	}

	@Test
	void failIfStaleRunningDoesNotPublishWhenConditionalUpdateLosesRace() {
		Instant cutoff = Instant.parse("2026-07-23T00:00:00Z");
		Instant failedAt = Instant.parse("2026-07-23T00:15:00Z");
		when(providerJobRepository.failIfStaleRunning(
				"provider-job-raced",
				ProviderJobStatus.RUNNING,
				ProviderJobStatus.FAILED,
				"PROVIDER_INTERRUPTED",
				cutoff,
				failedAt)).thenReturn(0);

		boolean recovered = providerJobService.failIfStaleRunning("provider-job-raced", cutoff, failedAt);

		assertFalse(recovered);
		verify(providerJobRepository, never()).findById("provider-job-raced");
		verify(streamEventService, never()).publish(any(String.class), any());
	}

	private ProviderJobEntity job(String id, ProviderJobStatus status) {
		ProviderJobEntity entity = new ProviderJobEntity();
		entity.setId(id);
		entity.setJobType(ProviderJobType.MUSIC_GEN);
		entity.setProviderType(ProviderType.MUSIC);
		entity.setProviderKey("worker-primary");
		entity.setQueueItemId("queue-001");
		entity.setStatus(status);
		entity.setExternalRef("worker-job-001");
		entity.setCorrelationId("corr-001");
		entity.setCreatedAt(Instant.parse("2026-07-22T23:00:00Z"));
		entity.setUpdatedAt(Instant.parse("2026-07-22T23:30:00Z"));
		return entity;
	}
}
