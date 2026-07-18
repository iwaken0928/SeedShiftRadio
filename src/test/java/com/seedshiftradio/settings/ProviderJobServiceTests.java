package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

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
}
