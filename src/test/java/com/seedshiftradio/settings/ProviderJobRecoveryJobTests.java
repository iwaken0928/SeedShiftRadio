package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.ProviderJobStatus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class ProviderJobRecoveryJobTests {

	@Mock
	ProviderJobService providerJobService;

	SimpleMeterRegistry meterRegistry;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
	}

	@Test
	void runOnceRecoversOnlyCandidatesThatStillMatchTheStaleRunningCondition() {
		Instant now = Instant.parse("2026-07-23T00:15:00Z");
		Instant cutoff = Instant.parse("2026-07-23T00:00:00Z");
		ProviderJobEntity stale = job("provider-job-stale");
		ProviderJobEntity raced = job("provider-job-raced");
		ProviderJobRecoveryJob recoveryJob = new ProviderJobRecoveryJob(
				providerJobService,
				meterRegistry,
				Duration.ofMinutes(15),
				2);
		when(providerJobService.findStaleRunning(cutoff, 2)).thenReturn(List.of(stale, raced));
		when(providerJobService.failIfStaleRunning("provider-job-stale", cutoff, now)).thenReturn(true);
		when(providerJobService.failIfStaleRunning("provider-job-raced", cutoff, now)).thenReturn(false);

		ProviderJobRecoveryJob.RecoveryResult result = recoveryJob.runOnce(now);

		assertEquals(now, result.checkedAt());
		assertEquals(cutoff, result.cutoff());
		assertEquals(2, result.scannedCount());
		assertEquals(1, result.recoveredCount());
		assertEquals(1.0D, meterRegistry.get(ProviderJobRecoveryJob.RECOVERED_COUNTER_NAME).counter().count());
		assertEquals(0.0D, meterRegistry.get(ProviderJobRecoveryJob.RECOVERY_FAILURE_COUNTER_NAME).counter().count());
		verifyNoMoreInteractions(providerJobService);
	}

	@Test
	void runOnceCountsFailureAndDoesNotAttemptAnyAutomaticRetry() {
		Instant now = Instant.parse("2026-07-23T00:15:00Z");
		Instant cutoff = Instant.parse("2026-07-23T00:00:00Z");
		ProviderJobRecoveryJob recoveryJob = new ProviderJobRecoveryJob(
				providerJobService,
				meterRegistry,
				Duration.ofMinutes(15),
				100);
		IllegalStateException failure = new IllegalStateException("database unavailable");
		when(providerJobService.findStaleRunning(cutoff, 100)).thenThrow(failure);

		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> recoveryJob.runOnce(now));

		assertEquals(failure, thrown);
		assertEquals(0.0D, meterRegistry.get(ProviderJobRecoveryJob.RECOVERED_COUNTER_NAME).counter().count());
		assertEquals(1.0D, meterRegistry.get(ProviderJobRecoveryJob.RECOVERY_FAILURE_COUNTER_NAME).counter().count());
		verify(providerJobService).findStaleRunning(cutoff, 100);
		verifyNoMoreInteractions(providerJobService);
	}

	@Test
	void constructorRejectsNonPositiveStaleThreshold() {
		assertThrows(IllegalArgumentException.class, () -> new ProviderJobRecoveryJob(
				providerJobService,
				meterRegistry,
				Duration.ZERO,
				100));
	}

	private ProviderJobEntity job(String id) {
		ProviderJobEntity entity = new ProviderJobEntity();
		entity.setId(id);
		entity.setStatus(ProviderJobStatus.RUNNING);
		return entity;
	}
}
