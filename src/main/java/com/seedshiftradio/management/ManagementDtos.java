package com.seedshiftradio.management;

import java.time.Instant;
import java.util.List;

import com.seedshiftradio.domain.PreGenerationRequestStatus;
import com.seedshiftradio.monitor.MonitorDtos.MonitorSummaryResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class ManagementDtos {

	private ManagementDtos() {
	}

	public record ManagementDashboardResponse(
			MonitorSummaryResponse system,
			long stationCount,
			long activeStationCount,
			long programTemplateCount,
			List<StationContentInventory> stations,
			List<PreGenerationResponse> recentPreGenerations,
			Instant updatedAt) {
	}

	public record StationContentInventory(
			String stationId,
			String stationName,
			boolean active,
			boolean programmingEnabled,
			long applicableProgramTemplateCount,
			long programCount,
			long preGeneratedProgramCount,
			long generatedAssetCount,
			long generatedAssetBytes,
			long scriptAssetCount,
			long audioAssetCount,
			long musicAssetCount,
			long musicAssetBytes,
			Instant latestProgramAt,
			Instant latestAssetAt,
			PreGenerationResponse latestPreGeneration) {
	}

	public record PreGenerationRequest(
			String programTemplateId,
			@Min(1) @Max(10) int targetProgramCount,
			@NotNull Boolean includeSpeech,
			@NotNull Boolean includeMusic) {
	}

	public record PreGenerationResponse(
			String id,
			String stationId,
			String sessionId,
			String programTemplateId,
			int targetProgramCount,
			boolean includeSpeech,
			boolean includeMusic,
			PreGenerationRequestStatus status,
			int materializedProgramCount,
			int materializedSegmentCount,
			int queuedMusicCount,
			String errorCode,
			Instant requestedAt,
			Instant startedAt,
			Instant completedAt,
			Instant updatedAt) {
	}
}
