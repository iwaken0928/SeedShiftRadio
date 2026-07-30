package com.seedshiftradio.management;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.PreGenerationRequestStatus;
import com.seedshiftradio.domain.ProgramBlockStatus;
import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.monitor.MonitorDtos.MonitorSummaryResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
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

	public record StationProgramContentResponse(
			String stationId,
			String stationName,
			List<ProgramContentDetail> programs,
			Instant updatedAt) {
	}

	public record ProgramContentDetail(
			String programBlockId,
			String sessionId,
			String programTemplateId,
			Integer programTemplateVersion,
			String title,
			ProgramBlockStatus status,
			boolean preGenerated,
			Integer plannedDurationMs,
			Instant startedAt,
			Instant endedAt,
			long segmentCount,
			long plannedSegmentCount,
			long generatingSegmentCount,
			long readySegmentCount,
			long failedSegmentCount,
			long generatedAssetCount,
			long generatedAssetBytes,
			long scriptAssetCount,
			long audioAssetCount,
			long musicAssetCount,
			Instant latestAssetAt,
			List<ProgramSegmentContent> segments) {
	}

	public record ProgramSegmentContent(
			String queueItemId,
			Integer sequenceNo,
			SegmentType segmentType,
			SlotRole slotRole,
			String title,
			QueueItemStatus status,
			String contentOrigin,
			Integer durationMs,
			String primaryAssetId,
			List<GeneratedAssetSummary> assets) {
	}

	public record GeneratedAssetSummary(
			String assetId,
			GeneratedAssetType assetType,
			long byteSize,
			Instant createdAt) {
	}

	public record PreGenerationRequest(
			String programTemplateId,
			@Min(1) @Max(10) int targetProgramCount,
			@NotNull Boolean includeSpeech,
			@NotNull Boolean includeMusic) {
	}

	public record StationContentDeletionRequest(
			@NotEmpty List<GeneratedAssetType> assetTypes) {
	}

	public record StationContentDeletionResponse(
			String stationId,
			Instant executedAt,
			int candidateAssetCount,
			int deletedAssetCount,
			int failedAssetCount,
			long reclaimedBytes,
			Map<GeneratedAssetType, Integer> deletedByType) {
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
