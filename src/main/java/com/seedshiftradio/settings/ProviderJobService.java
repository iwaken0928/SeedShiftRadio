package com.seedshiftradio.settings;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.domain.ProviderJobStatus;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.monitor.OperationalEventService;
import com.seedshiftradio.stream.StreamEventService;

@Service
public class ProviderJobService {

	private final ProviderJobRepository providerJobRepository;
	private final StreamEventService streamEventService;
	private final OperationalEventService operationalEventService;

	public ProviderJobService(
			ProviderJobRepository providerJobRepository,
			StreamEventService streamEventService,
			OperationalEventService operationalEventService) {
		this.providerJobRepository = providerJobRepository;
		this.streamEventService = streamEventService;
		this.operationalEventService = operationalEventService;
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
		ProviderJobEntity saved = providerJobRepository.save(entity);
		publishAuditEvent("provider.job.queued", saved);
		return saved;
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
		ProviderJobEntity saved = providerJobRepository.save(entity);
		publishAuditEvent("provider.job.running", saved);
		return saved;
	}

	@Transactional
	public ProviderJobEntity markSucceeded(String providerJobId) {
		ProviderJobEntity entity = providerJobRepository.findById(providerJobId).orElseThrow();
		entity.setStatus(ProviderJobStatus.SUCCEEDED);
		entity.setEndedAt(Instant.now());
		entity.setErrorCode(null);
		ProviderJobEntity saved = providerJobRepository.save(entity);
		publishAuditEvent("provider.job.succeeded", saved);
		return saved;
	}

	@Transactional
	public ProviderJobEntity markFailed(String providerJobId, String errorCode) {
		return markFailed(providerJobId, ProviderErrorClassifier.normalizeExternalCode(errorCode));
	}

	@Transactional
	public ProviderJobEntity markFailed(String providerJobId, ProviderErrorCode errorCode) {
		ProviderJobEntity entity = providerJobRepository.findById(providerJobId).orElseThrow();
		entity.setStatus(ProviderJobStatus.FAILED);
		entity.setEndedAt(Instant.now());
		entity.setErrorCode((errorCode == null ? ProviderErrorCode.PROVIDER_BAD_RESPONSE : errorCode).name());
		ProviderJobEntity saved = providerJobRepository.save(entity);
		publishAuditEvent("provider.job.failed", saved);
		return saved;
	}

	@Transactional(readOnly = true)
	public List<ProviderJobEntity> findStaleRunning(Instant cutoff, int limit) {
		int effectiveLimit = Math.max(1, limit);
		return providerJobRepository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
				ProviderJobStatus.RUNNING,
				cutoff,
				PageRequest.of(0, effectiveLimit));
	}

	@Transactional
	public boolean failIfStaleRunning(String providerJobId, Instant cutoff, Instant failedAt) {
		int updated = providerJobRepository.failIfStaleRunning(
				providerJobId,
				ProviderJobStatus.RUNNING,
				ProviderJobStatus.FAILED,
				ProviderErrorCode.PROVIDER_INTERRUPTED.name(),
				cutoff,
				failedAt);
		if (updated == 0) {
			return false;
		}
		ProviderJobEntity failed = providerJobRepository.findById(providerJobId).orElseThrow();
		publishAuditEvent("provider.job.failed", failed);
		return true;
	}

	private void publishAuditEvent(String eventName, ProviderJobEntity entity) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("providerJobId", entity.getId());
		payload.put("jobType", entity.getJobType().name());
		payload.put("providerType", entity.getProviderType().name());
		putIfPresent(payload, "providerKey", entity.getProviderKey());
		putIfPresent(payload, "queueItemId", entity.getQueueItemId());
		payload.put("status", entity.getStatus().name());
		putIfPresent(payload, "externalRef", entity.getExternalRef());
		putIfPresent(payload, "errorCode", entity.getErrorCode());
		payload.put("correlationId", entity.getCorrelationId());
		streamEventService.publish(eventName, payload);
		operationalEventService.recordProviderJob(eventName, entity);
	}

	private static void putIfPresent(Map<String, Object> payload, String key, String value) {
		if (value != null && !value.isBlank()) {
			payload.put(key, value);
		}
	}

	private String nextId() {
		return "provider-job-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
