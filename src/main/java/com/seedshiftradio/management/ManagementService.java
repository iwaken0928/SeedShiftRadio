package com.seedshiftradio.management;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.PreGenerationRequestStatus;
import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.management.ManagementDtos.GeneratedAssetSummary;
import com.seedshiftradio.management.ManagementDtos.ManagementDashboardResponse;
import com.seedshiftradio.management.ManagementDtos.PreGenerationRequest;
import com.seedshiftradio.management.ManagementDtos.PreGenerationResponse;
import com.seedshiftradio.management.ManagementDtos.ProgramContentDetail;
import com.seedshiftradio.management.ManagementDtos.ProgramSegmentContent;
import com.seedshiftradio.management.ManagementDtos.StationContentDeletionRequest;
import com.seedshiftradio.management.ManagementDtos.StationContentDeletionResponse;
import com.seedshiftradio.management.ManagementDtos.StationContentInventory;
import com.seedshiftradio.management.ManagementDtos.StationProgramContentResponse;
import com.seedshiftradio.monitor.MonitorService;
import com.seedshiftradio.monitor.OperationalEventService;
import com.seedshiftradio.programming.ProgramTemplateRepository;
import com.seedshiftradio.programming.ProgrammingService;
import com.seedshiftradio.radio.PlayoutSessionEntity;
import com.seedshiftradio.radio.PlayoutSessionRepository;
import com.seedshiftradio.radio.ProgramBlockEntity;
import com.seedshiftradio.radio.ProgramBlockRepository;
import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.radio.QueueItemRepository;
import com.seedshiftradio.radio.RadioService;
import com.seedshiftradio.settings.GeneratedAssetEntity;
import com.seedshiftradio.settings.GeneratedAssetRepository;
import com.seedshiftradio.settings.GeneratedAssetService;
import com.seedshiftradio.settings.ProviderRuntimeException;
import com.seedshiftradio.station.StationEntity;
import com.seedshiftradio.station.StationRepository;

@Service
public class ManagementService {

	private final MonitorService monitorService;
	private final StationRepository stationRepository;
	private final ProgramTemplateRepository programTemplateRepository;
	private final ProgramBlockRepository programBlockRepository;
	private final GeneratedAssetRepository generatedAssetRepository;
	private final GeneratedAssetService generatedAssetService;
	private final PreGenerationRequestRepository preGenerationRequestRepository;
	private final PlayoutSessionRepository playoutSessionRepository;
	private final ProgrammingService programmingService;
	private final RadioService radioService;
	private final ApplicationEventPublisher eventPublisher;
	private final OperationalEventService operationalEventService;
	private final QueueItemRepository queueItemRepository;

	@Autowired
	public ManagementService(
			MonitorService monitorService,
			StationRepository stationRepository,
			ProgramTemplateRepository programTemplateRepository,
			ProgramBlockRepository programBlockRepository,
			GeneratedAssetRepository generatedAssetRepository,
			GeneratedAssetService generatedAssetService,
			PreGenerationRequestRepository preGenerationRequestRepository,
			PlayoutSessionRepository playoutSessionRepository,
			ProgrammingService programmingService,
			RadioService radioService,
			ApplicationEventPublisher eventPublisher,
			OperationalEventService operationalEventService,
			QueueItemRepository queueItemRepository) {
		this.monitorService = monitorService;
		this.stationRepository = stationRepository;
		this.programTemplateRepository = programTemplateRepository;
		this.programBlockRepository = programBlockRepository;
		this.generatedAssetRepository = generatedAssetRepository;
		this.generatedAssetService = generatedAssetService;
		this.preGenerationRequestRepository = preGenerationRequestRepository;
		this.playoutSessionRepository = playoutSessionRepository;
		this.programmingService = programmingService;
		this.radioService = radioService;
		this.eventPublisher = eventPublisher;
		this.operationalEventService = operationalEventService;
		this.queueItemRepository = queueItemRepository;
	}

	ManagementService(
			MonitorService monitorService,
			StationRepository stationRepository,
			ProgramTemplateRepository programTemplateRepository,
			ProgramBlockRepository programBlockRepository,
			GeneratedAssetRepository generatedAssetRepository,
			GeneratedAssetService generatedAssetService,
			PreGenerationRequestRepository preGenerationRequestRepository,
			PlayoutSessionRepository playoutSessionRepository,
			ProgrammingService programmingService,
			RadioService radioService,
			ApplicationEventPublisher eventPublisher,
			OperationalEventService operationalEventService) {
		this(
				monitorService,
				stationRepository,
				programTemplateRepository,
				programBlockRepository,
				generatedAssetRepository,
				generatedAssetService,
				preGenerationRequestRepository,
				playoutSessionRepository,
				programmingService,
				radioService,
				eventPublisher,
				operationalEventService,
				null);
	}

	@Transactional(readOnly = true)
	public ManagementDashboardResponse dashboard() {
		List<StationEntity> stations = stationRepository.findAll().stream()
				.sorted(Comparator.comparing(StationEntity::getName))
				.toList();
		List<PreGenerationResponse> recent = preGenerationRequestRepository.findTop10ByOrderByRequestedAtDesc().stream()
				.map(ManagementService::toResponse)
				.toList();
		List<StationContentInventory> inventories = stations.stream()
				.map(station -> inventory(station, latestPreGeneration(station.getId())))
				.toList();
		return new ManagementDashboardResponse(
				monitorService.summary(),
				stations.size(),
				stations.stream().filter(StationEntity::isActive).count(),
				programTemplateRepository.count(),
				inventories,
				recent,
				Instant.now());
	}

	@Transactional(readOnly = true)
	public StationContentInventory stationInventory(String stationId) {
		StationEntity station = stationRepository.findById(stationId)
				.orElseThrow(() -> notFound("stationId", stationId));
		return inventory(station, latestPreGeneration(stationId));
	}

	@Transactional(readOnly = true)
	public StationProgramContentResponse stationPrograms(String stationId) {
		StationEntity station = stationRepository.findById(stationId)
				.orElseThrow(() -> notFound("stationId", stationId));
		List<ProgramBlockEntity> blocks = programBlockRepository.findTop100ByStationIdOrderByStartedAtDesc(stationId);
		if (blocks.isEmpty()) {
			return new StationProgramContentResponse(stationId, station.getName(), List.of(), Instant.now());
		}

		List<String> blockIds = blocks.stream().map(ProgramBlockEntity::getId).toList();
		List<QueueItemEntity> items = queueItemRepository.findByProgramBlockIdInOrderByProgramBlockIdAscSequenceNoAsc(blockIds);
		Map<String, List<QueueItemEntity>> itemsByBlock = new LinkedHashMap<>();
		for (QueueItemEntity item : items) {
			itemsByBlock.computeIfAbsent(item.getProgramBlockId(), ignored -> new java.util.ArrayList<>()).add(item);
		}

		List<String> queueItemIds = items.stream().map(QueueItemEntity::getId).toList();
		List<GeneratedAssetEntity> assets = queueItemIds.isEmpty()
				? List.of()
				: generatedAssetRepository.findByQueueItemIdIn(queueItemIds).stream()
						.filter(asset -> asset.getByteSize() != null && asset.getByteSize() > 0)
						.toList();
		Map<String, List<GeneratedAssetEntity>> assetsByQueueItem = new HashMap<>();
		for (GeneratedAssetEntity asset : assets) {
			assetsByQueueItem.computeIfAbsent(asset.getQueueItemId(), ignored -> new java.util.ArrayList<>()).add(asset);
		}

		Map<String, PlayoutSessionEntity> sessions = new HashMap<>();
		playoutSessionRepository.findAllById(blocks.stream().map(ProgramBlockEntity::getSessionId).distinct().toList())
				.forEach(session -> sessions.put(session.getId(), session));
		List<ProgramContentDetail> programs = blocks.stream()
				.map(block -> toProgramContentDetail(
						block,
						sessions.get(block.getSessionId()),
						itemsByBlock.getOrDefault(block.getId(), List.of()),
						assetsByQueueItem))
				.toList();
		return new StationProgramContentResponse(stationId, station.getName(), programs, Instant.now());
	}

	@Transactional
	public PreGenerationResponse requestPreGeneration(String stationId, PreGenerationRequest request) {
		StationEntity station = stationRepository.findById(stationId)
				.orElseThrow(() -> notFound("stationId", stationId));
		if (!station.isActive()) {
			throw new ApiException(
					HttpStatus.CONFLICT,
					"CONFLICT",
					"無効な局は事前生成できません。",
					Map.of("stationId", stationId));
		}
		if (!Boolean.TRUE.equals(request.includeSpeech()) && !Boolean.TRUE.equals(request.includeMusic())) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					"音声コンテンツまたは音楽のいずれかを生成対象に指定してください。",
					Map.of("includeSpeech", false, "includeMusic", false));
		}
		programmingService.resolvePreGenerationPlan(
				stationId,
				request.programTemplateId(),
				OffsetDateTime.now());

		String requestId = nextId("pregen");
		String sessionId = nextId("playout-pregen");
		PlayoutSessionEntity session = PlayoutSessionEntity.preGeneration(sessionId, stationId, requestId);
		playoutSessionRepository.save(session);

		PreGenerationRequestEntity entity = new PreGenerationRequestEntity();
		entity.setId(requestId);
		entity.setStationId(stationId);
		entity.setSessionId(sessionId);
		entity.setProgramTemplateId(blankToNull(request.programTemplateId()));
		entity.setTargetProgramCount(request.targetProgramCount());
		entity.setIncludeSpeech(Boolean.TRUE.equals(request.includeSpeech()));
		entity.setIncludeMusic(Boolean.TRUE.equals(request.includeMusic()));
		entity.setStatus(PreGenerationRequestStatus.QUEUED);
		entity = preGenerationRequestRepository.save(entity);
		eventPublisher.publishEvent(new PreGenerationRequested(entity.getId()));
		return toResponse(entity);
	}

	@Transactional
	public StationContentDeletionResponse deleteStationContent(
			String stationId,
			StationContentDeletionRequest request) {
		stationRepository.findById(stationId)
				.orElseThrow(() -> notFound("stationId", stationId));
		GeneratedAssetService.StationContentDeletionResult result =
				generatedAssetService.deletePreGeneratedStationContent(
						stationId,
						request.assetTypes() == null
								? java.util.Set.of()
								: java.util.Set.copyOf(request.assetTypes()));
		operationalEventService.recordStationContentDeletion(
				stationId,
				result.deletedAssetCount(),
				result.failedAssetCount(),
				result.reclaimedBytes());
		return new StationContentDeletionResponse(
				result.stationId(),
				result.executedAt(),
				result.candidateAssetCount(),
				result.deletedAssetCount(),
				result.failedAssetCount(),
				result.reclaimedBytes(),
				result.deletedByType());
	}

	@Transactional
	public StationContentDeletionResponse deleteProgramContent(
			String stationId,
			String programBlockId,
			StationContentDeletionRequest request) {
		stationRepository.findById(stationId)
				.orElseThrow(() -> notFound("stationId", stationId));
		ProgramBlockEntity block = programBlockRepository.findById(programBlockId)
				.filter(candidate -> stationId.equals(candidate.getStationId()))
				.orElseThrow(() -> notFound("programBlockId", programBlockId));
		boolean preGenerated = playoutSessionRepository.findById(block.getSessionId())
				.map(PlayoutSessionEntity::isPreGeneration)
				.orElse(false);
		if (!preGenerated) {
			throw new ApiException(
					HttpStatus.CONFLICT,
					"CONFLICT",
					"通常放送の番組コンテンツはこの操作では削除できません。",
					Map.of("programBlockId", programBlockId));
		}
		GeneratedAssetService.StationContentDeletionResult result =
				generatedAssetService.deletePreGeneratedProgramContent(
						stationId,
						programBlockId,
						request.assetTypes() == null
								? java.util.Set.of()
								: java.util.Set.copyOf(request.assetTypes()));
		operationalEventService.recordStationContentDeletion(
				stationId,
				result.deletedAssetCount(),
				result.failedAssetCount(),
				result.reclaimedBytes());
		return new StationContentDeletionResponse(
				result.stationId(),
				result.executedAt(),
				result.candidateAssetCount(),
				result.deletedAssetCount(),
				result.failedAssetCount(),
				result.reclaimedBytes(),
				result.deletedByType());
	}

	@Transactional
	public void runPreGeneration(String requestId) {
		PreGenerationRequestEntity entity = preGenerationRequestRepository.findById(requestId).orElse(null);
		if (entity == null || entity.getStatus() != PreGenerationRequestStatus.QUEUED) {
			return;
		}
		entity.setStatus(PreGenerationRequestStatus.RUNNING);
		entity.setStartedAt(Instant.now());
		preGenerationRequestRepository.save(entity);
		RadioService.PreGenerationResult result = radioService.preGenerateOffAirContent(
				entity.getSessionId(),
				entity.getProgramTemplateId(),
				entity.getTargetProgramCount(),
				entity.isIncludeSpeech(),
				entity.isIncludeMusic());
		entity = preGenerationRequestRepository.findById(requestId).orElseThrow();
		entity.setStatus(PreGenerationRequestStatus.MATERIALIZED);
		entity.setMaterializedProgramCount(result.materializedProgramCount());
		entity.setMaterializedSegmentCount(result.materializedSegmentCount());
		entity.setQueuedMusicCount(result.queuedMusicCount());
		entity.setCompletedAt(Instant.now());
		preGenerationRequestRepository.save(entity);
	}

	@Transactional
	public void markPreGenerationFailed(String requestId, RuntimeException exception) {
		preGenerationRequestRepository.findById(requestId).ifPresent(entity -> {
			String errorCode = resolvePreGenerationErrorCode(exception);
			entity.setStatus(PreGenerationRequestStatus.FAILED);
			entity.setErrorCode(errorCode);
			entity.setCompletedAt(Instant.now());
			preGenerationRequestRepository.save(entity);
			operationalEventService.recordPreGenerationFailure(
					requestId,
					playoutSessionRepository.findById(entity.getSessionId())
							.map(PlayoutSessionEntity::getCorrelationId)
							.orElse(null),
					errorCode,
					preGenerationFailureMessage(errorCode));
		});
	}

	private static String resolvePreGenerationErrorCode(Throwable exception) {
		Throwable current = exception;
		while (current != null) {
			if (current instanceof ProviderRuntimeException providerException) {
				return providerException.errorCode();
			}
			current = current.getCause();
		}
		return "PRE_GENERATION_FAILED";
	}

	private static String preGenerationFailureMessage(String errorCode) {
		return switch (errorCode) {
			case "PROVIDER_TIMEOUT" -> "事前生成中に Provider がタイムアウトしました。実生成用 timeout とモデルのコールドスタート時間を確認してください。";
			case "PROVIDER_UNREACHABLE" -> "事前生成中に Provider へ接続できませんでした。";
			case "PROVIDER_BAD_RESPONSE" -> "事前生成中に Provider が期待した形式の応答を返しませんでした。";
			case "PROVIDER_RESOURCE_EXHAUSTED" -> "事前生成中に Provider の処理資源が不足しました。";
			default -> "事前生成処理で内部エラーが発生しました。requestId と同時刻の構造化運用ログを確認してください。";
		};
	}

	private StationContentInventory inventory(StationEntity station, PreGenerationResponse latestPreGeneration) {
		Map<GeneratedAssetType, AssetTotals> assets = new EnumMap<>(GeneratedAssetType.class);
		Instant latestAssetAt = null;
		for (GeneratedAssetRepository.StationAssetStats stats : generatedAssetRepository.summarizeByStationId(station.getId())) {
			GeneratedAssetType type = GeneratedAssetType.valueOf(stats.getAssetType());
			assets.put(type, new AssetTotals(stats.getAssetCount(), stats.getByteSize()));
			if (stats.getLatestCreatedAt() != null && (latestAssetAt == null || stats.getLatestCreatedAt().isAfter(latestAssetAt))) {
				latestAssetAt = stats.getLatestCreatedAt();
			}
		}
		AssetTotals scripts = assets.getOrDefault(GeneratedAssetType.SCRIPT, AssetTotals.ZERO);
		AssetTotals audio = assets.getOrDefault(GeneratedAssetType.AUDIO, AssetTotals.ZERO);
		AssetTotals music = assets.getOrDefault(GeneratedAssetType.MUSIC, AssetTotals.ZERO);
		long totalAssetCount = scripts.count() + audio.count() + music.count();
		long totalAssetBytes = scripts.bytes() + audio.bytes() + music.bytes();
		Instant latestProgramAt = programBlockRepository.findTopByStationIdOrderByStartedAtDesc(station.getId())
				.map(block -> block.getStartedAt())
				.orElse(null);
		return new StationContentInventory(
				station.getId(),
				station.getName(),
				station.isActive(),
				station.isProgrammingEnabled(),
				programTemplateRepository.countActiveApplicableToStation(station.getId()),
				programBlockRepository.countByStationId(station.getId()),
				programBlockRepository.countPreGeneratedByStationId(station.getId()),
				totalAssetCount,
				totalAssetBytes,
				scripts.count(),
				audio.count(),
				music.count(),
				music.bytes(),
				latestProgramAt,
				latestAssetAt,
				latestPreGeneration);
	}

	private ProgramContentDetail toProgramContentDetail(
			ProgramBlockEntity block,
			PlayoutSessionEntity session,
			List<QueueItemEntity> items,
			Map<String, List<GeneratedAssetEntity>> assetsByQueueItem) {
		List<GeneratedAssetEntity> assets = items.stream()
				.flatMap(item -> assetsByQueueItem.getOrDefault(item.getId(), List.of()).stream())
				.toList();
		Instant latestAssetAt = assets.stream()
				.map(GeneratedAssetEntity::getCreatedAt)
				.filter(java.util.Objects::nonNull)
				.max(Instant::compareTo)
				.orElse(null);
		List<ProgramSegmentContent> segments = items.stream()
				.map(item -> new ProgramSegmentContent(
						item.getId(),
						item.getSequenceNo(),
						item.getSegmentType(),
						item.getSlotRole(),
						item.getTitle(),
						item.getStatus(),
						item.getContentOrigin(),
						item.getDurationMs(),
						item.getAssetId(),
						assetsByQueueItem.getOrDefault(item.getId(), List.of()).stream()
								.map(asset -> new GeneratedAssetSummary(
										asset.getId(),
										asset.getAssetType(),
										asset.getByteSize() == null ? 0L : asset.getByteSize(),
										asset.getCreatedAt()))
								.toList()))
				.toList();
		return new ProgramContentDetail(
				block.getId(),
				block.getSessionId(),
				block.getProgramTemplateId(),
				block.getProgramTemplateVersion(),
				block.getTitle(),
				block.getStatus(),
				session != null && session.isPreGeneration(),
				block.getPlannedDurationMs(),
				block.getStartedAt(),
				block.getEndedAt(),
				items.size(),
				countStatus(items, QueueItemStatus.PLANNED),
				countStatus(items, QueueItemStatus.GENERATING),
				countStatus(items, QueueItemStatus.READY),
				countStatus(items, QueueItemStatus.FAILED),
				assets.size(),
				assets.stream().mapToLong(asset -> asset.getByteSize() == null ? 0L : asset.getByteSize()).sum(),
				countAssetType(assets, GeneratedAssetType.SCRIPT),
				countAssetType(assets, GeneratedAssetType.AUDIO),
				countAssetType(assets, GeneratedAssetType.MUSIC),
				latestAssetAt,
				segments);
	}

	private long countStatus(List<QueueItemEntity> items, QueueItemStatus status) {
		return items.stream().filter(item -> item.getStatus() == status).count();
	}

	private long countAssetType(List<GeneratedAssetEntity> assets, GeneratedAssetType assetType) {
		return assets.stream().filter(asset -> asset.getAssetType() == assetType).count();
	}

	private PreGenerationResponse latestPreGeneration(String stationId) {
		return preGenerationRequestRepository.findFirstByStationIdOrderByRequestedAtDesc(stationId)
				.map(ManagementService::toResponse)
				.orElse(null);
	}

	private static PreGenerationResponse toResponse(PreGenerationRequestEntity entity) {
		return new PreGenerationResponse(
				entity.getId(),
				entity.getStationId(),
				entity.getSessionId(),
				entity.getProgramTemplateId(),
				entity.getTargetProgramCount(),
				entity.isIncludeSpeech(),
				entity.isIncludeMusic(),
				entity.getStatus(),
				entity.getMaterializedProgramCount(),
				entity.getMaterializedSegmentCount(),
				entity.getQueuedMusicCount(),
				entity.getErrorCode(),
				entity.getRequestedAt(),
				entity.getStartedAt(),
				entity.getCompletedAt(),
				entity.getUpdatedAt());
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static ApiException notFound(String field, String value) {
		return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "指定されたデータが見つかりません。", Map.of(field, value));
	}

	private static String nextId(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	private record AssetTotals(long count, long bytes) {
		private static final AssetTotals ZERO = new AssetTotals(0L, 0L);
	}
}
