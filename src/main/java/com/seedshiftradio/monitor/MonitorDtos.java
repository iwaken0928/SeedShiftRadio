package com.seedshiftradio.monitor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.seedshiftradio.domain.PlayoutState;
import com.seedshiftradio.domain.ProviderJobStatus;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.settings.GeneratedAssetService;
import com.seedshiftradio.settings.SettingsDtos;

public final class MonitorDtos {

	private MonitorDtos() {
	}

	public record MonitorSummaryResponse(
			String sessionId,
			String stationId,
			PlayoutState state,
			Integer bufferReadyCount,
			long queueReadyDurationMs,
			long pendingLetterCount,
			boolean degraded,
			Map<String, SettingsDtos.ProviderHealthPayload> providerHealth,
			GeneratedAssetService.CacheMetricsSnapshot cache,
			ArchiveMetrics archive,
			List<ProviderJobSummary> runningJobs,
			List<ProviderJobSummary> recentErrors,
			List<AuditEventSummary> auditEvents,
			Instant updatedAt) {
	}

	public record ArchiveMetrics(
			long eligibleArchiveCount,
			long totalArchiveCount,
			long archiveReplayCount,
			long totalPlaybackCount,
			double archiveReplayRate) {
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

	public record OperationalEventSummary(
			String id,
			String level,
			String category,
			String eventType,
			String sourceId,
			String correlationId,
			String providerType,
			String providerKey,
			String errorCode,
			String message,
			Instant occurredAt) {
	}

	public record AssetConsistencyResponse(
			Instant checkedAt,
			long assetCount,
			long checkedAssetCount,
			long missingFileCount,
			long byteSizeMismatchCount,
			long contentHashMismatchCount,
			long orphanFileCount,
			long unreadableFileCount,
			long issueCount,
			boolean issuesTruncated,
			List<GeneratedAssetService.AssetConsistencyIssue> issues) {

		public static AssetConsistencyResponse from(GeneratedAssetService.AssetConsistencyReport report) {
			return new AssetConsistencyResponse(
					report.checkedAt(),
					report.assetCount(),
					report.checkedAssetCount(),
					report.missingFileCount(),
					report.byteSizeMismatchCount(),
					report.contentHashMismatchCount(),
					report.orphanFileCount(),
					report.unreadableFileCount(),
					report.issueCount(),
					report.issuesTruncated(),
					report.issues());
		}
	}
}
