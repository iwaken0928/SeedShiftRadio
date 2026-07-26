package com.seedshiftradio.management;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.PreGenerationRequestStatus;
import com.seedshiftradio.management.ManagementDtos.ManagementDashboardResponse;
import com.seedshiftradio.management.ManagementDtos.PreGenerationRequest;
import com.seedshiftradio.management.ManagementDtos.PreGenerationResponse;
import com.seedshiftradio.management.ManagementDtos.StationContentInventory;
import com.seedshiftradio.monitor.MonitorService;
import com.seedshiftradio.monitor.OperationalEventService;
import com.seedshiftradio.programming.ProgramTemplateRepository;
import com.seedshiftradio.programming.ProgrammingService;
import com.seedshiftradio.radio.PlayoutSessionEntity;
import com.seedshiftradio.radio.PlayoutSessionRepository;
import com.seedshiftradio.radio.ProgramBlockRepository;
import com.seedshiftradio.radio.RadioService;
import com.seedshiftradio.settings.GeneratedAssetRepository;
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
	private final PreGenerationRequestRepository preGenerationRequestRepository;
	private final PlayoutSessionRepository playoutSessionRepository;
	private final ProgrammingService programmingService;
	private final RadioService radioService;
	private final ApplicationEventPublisher eventPublisher;
	private final OperationalEventService operationalEventService;

	public ManagementService(
			MonitorService monitorService,
			StationRepository stationRepository,
			ProgramTemplateRepository programTemplateRepository,
			ProgramBlockRepository programBlockRepository,
			GeneratedAssetRepository generatedAssetRepository,
			PreGenerationRequestRepository preGenerationRequestRepository,
			PlayoutSessionRepository playoutSessionRepository,
			ProgrammingService programmingService,
			RadioService radioService,
			ApplicationEventPublisher eventPublisher,
			OperationalEventService operationalEventService) {
		this.monitorService = monitorService;
		this.stationRepository = stationRepository;
		this.programTemplateRepository = programTemplateRepository;
		this.programBlockRepository = programBlockRepository;
		this.generatedAssetRepository = generatedAssetRepository;
		this.preGenerationRequestRepository = preGenerationRequestRepository;
		this.playoutSessionRepository = playoutSessionRepository;
		this.programmingService = programmingService;
		this.radioService = radioService;
		this.eventPublisher = eventPublisher;
		this.operationalEventService = operationalEventService;
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
