package com.seedshiftradio.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.settings.ProviderJobEntity;

@ExtendWith(MockitoExtension.class)
class OperationalEventServiceTests {

	@Mock
	OperationalEventRepository repository;

	@Test
	void failedProviderJobStoresOnlySafeDiagnosticMetadata() {
		OperationalEventService service = new OperationalEventService(repository);
		ProviderJobEntity job = mock(ProviderJobEntity.class);
		when(job.getId()).thenReturn("provider-job-001");
		when(job.getJobType()).thenReturn(ProviderJobType.SCRIPT_GEN);
		when(job.getProviderType()).thenReturn(ProviderType.LLM);
		when(job.getProviderKey()).thenReturn("ollama");
		when(job.getCorrelationId()).thenReturn("corr-001");
		when(job.getErrorCode()).thenReturn("PROVIDER_TIMEOUT");

		service.recordProviderJob("provider.job.failed", job);

		ArgumentCaptor<OperationalEventEntity> captor = ArgumentCaptor.forClass(OperationalEventEntity.class);
		verify(repository).save(captor.capture());
		OperationalEventEntity saved = captor.getValue();
		assertEquals("ERROR", saved.getLevel());
		assertEquals("PROVIDER_JOB", saved.getCategory());
		assertEquals("ollama", saved.getProviderKey());
		assertEquals("PROVIDER_TIMEOUT", saved.getErrorCode());
		assertFalse(saved.getMessage().contains("prompt"));
		assertFalse(saved.getMessage().contains("apiKey"));
		verify(repository).deleteByOccurredAtBefore(saved.getOccurredAt().minus(java.time.Duration.ofDays(30)));
	}

	@Test
	void recentCapsRequestedLimitAtTwoHundred() {
		when(repository.findAllByOrderByOccurredAtDesc(any(Pageable.class))).thenReturn(List.of());
		OperationalEventService service = new OperationalEventService(repository);

		service.recent(999);

		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(repository).findAllByOrderByOccurredAtDesc(captor.capture());
		assertEquals(200, captor.getValue().getPageSize());
	}
}
