package com.seedshiftradio.monitor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.seedshiftradio.domain.PlayoutState;
import com.seedshiftradio.domain.ProviderJobStatus;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.settings.SettingsDtos;

public final class MonitorDtos {

	private MonitorDtos() {
	}

	public record MonitorSummaryResponse(
			String sessionId,
			String stationId,
			PlayoutState state,
			Integer bufferReadyCount,
			long pendingLetterCount,
			boolean degraded,
			Map<String, SettingsDtos.ProviderHealthPayload> providerHealth,
			List<ProviderJobSummary> runningJobs,
			List<ProviderJobSummary> recentErrors,
			List<AuditEventSummary> auditEvents,
			Instant updatedAt) {
	}

	public record ProviderJobSummary(
			String id,
			ProviderJobType jobType,
			ProviderType providerType,
			String providerKey,
			String queueItemId,
			ProviderJobStatus status,
			String externalRef,
			String errorCode,
			Instant startedAt,
			Instant endedAt,
			Instant createdAt,
			Instant updatedAt) {
	}

	public record AuditEventSummary(
			String id,
			String eventType,
			Instant occurredAt,
			String summary) {
	}
}
