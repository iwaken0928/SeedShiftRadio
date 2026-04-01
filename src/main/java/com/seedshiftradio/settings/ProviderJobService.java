package com.seedshiftradio.settings;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.domain.ProviderJobStatus;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;

@Service
public class ProviderJobService {

	private final ProviderJobRepository providerJobRepository;

	public ProviderJobService(ProviderJobRepository providerJobRepository) {
		this.providerJobRepository = providerJobRepository;
	}

	@Transactional
	public ProviderJobEntity createQueuedJob(
			ProviderJobType jobType,
			ProviderType providerType,
			String providerKey,
			String queueItemId,
			String correlationId) {
		ProviderJobEntity entity = new ProviderJobEntity();
		entity.setId(nextId());
		entity.setJobType(jobType);
		entity.setProviderType(providerType);
		entity.setProviderKey(providerKey);
		entity.setQueueItemId(queueItemId);
		entity.setStatus(ProviderJobStatus.QUEUED);
		entity.setCorrelationId(correlationId);
		return providerJobRepository.save(entity);
	}

	@Transactional
	public ProviderJobEntity markRunning(String providerJobId, String externalRef) {
		return markRunning(providerJobId, null, externalRef);
	}

	@Transactional
	public ProviderJobEntity markRunning(String providerJobId, String providerKey, String externalRef) {
		ProviderJobEntity entity = providerJobRepository.findById(providerJobId).orElseThrow();
		entity.setStatus(ProviderJobStatus.RUNNING);
		if (providerKey != null && !providerKey.isBlank()) {
			entity.setProviderKey(providerKey);
		}
		entity.setExternalRef(externalRef);
		entity.setStartedAt(Instant.now());
		return providerJobRepository.save(entity);
	}

	@Transactional
	public ProviderJobEntity markSucceeded(String providerJobId) {
		ProviderJobEntity entity = providerJobRepository.findById(providerJobId).orElseThrow();
		entity.setStatus(ProviderJobStatus.SUCCEEDED);
		entity.setEndedAt(Instant.now());
		entity.setErrorCode(null);
		return providerJobRepository.save(entity);
	}

	@Transactional
	public ProviderJobEntity markFailed(String providerJobId, String errorCode) {
		ProviderJobEntity entity = providerJobRepository.findById(providerJobId).orElseThrow();
		entity.setStatus(ProviderJobStatus.FAILED);
		entity.setEndedAt(Instant.now());
		entity.setErrorCode(errorCode);
		return providerJobRepository.save(entity);
	}

	private String nextId() {
		return "provider-job-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
