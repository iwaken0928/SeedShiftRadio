package com.seedshiftradio.radio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.domain.PlayHistoryResultStatus;
import com.seedshiftradio.domain.PlayoutState;
import com.seedshiftradio.domain.ProgramBlockSlotStatus;
import com.seedshiftradio.domain.ProgramBlockStatus;
import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport.PreGenerationProfile;
import com.seedshiftradio.programming.ProgrammingService;
import com.seedshiftradio.programming.ProgrammingService.ResolvedProgramPlan;
import com.seedshiftradio.programming.ProgrammingService.ResolvedSlot;
import com.seedshiftradio.programming.StationProgrammingPolicyRepository;
import com.seedshiftradio.settings.AssetService;
import com.seedshiftradio.settings.RadioSettingsStore;
import com.seedshiftradio.settings.SettingsDocument;
import com.seedshiftradio.station.StationRepository;
import com.seedshiftradio.stream.StreamEventService;

@Service
public class RadioService {

	private final StationRepository stationRepository;
	private final ProgrammingService programmingService;
	private final StationProgrammingPolicyRepository programmingPolicyRepository;
	private final PlayoutSessionRepository playoutSessionRepository;
	private final ProgramBlockRepository programBlockRepository;
	private final ProgramBlockSlotRepository programBlockSlotRepository;
	private final QueueItemRepository queueItemRepository;
	private final StreamEventService streamEventService;
	private final RadioSettingsStore settingsStore;
	private final AssetService assetService;
	private final ClientCapabilitiesService clientCapabilitiesService;
	private final ScriptGenerationService scriptGenerationService;
	private final PlayHistoryService playHistoryService;
	private final LetterSegmentBinder letterSegmentBinder;
	private final BroadcastArchiveService broadcastArchiveService;
	private final ApplicationEventPublisher eventPublisher;
	private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

	public RadioService(
			StationRepository stationRepository,
			ProgrammingService programmingService,
			StationProgrammingPolicyRepository programmingPolicyRepository,
			PlayoutSessionRepository playoutSessionRepository,
			ProgramBlockRepository programBlockRepository,
			ProgramBlockSlotRepository programBlockSlotRepository,
			QueueItemRepository queueItemRepository,
			StreamEventService streamEventService,
			RadioSettingsStore settingsStore,
			AssetService assetService,
			ClientCapabilitiesService clientCapabilitiesService,
			ScriptGenerationService scriptGenerationService,
			PlayHistoryService playHistoryService,
			LetterSegmentBinder letterSegmentBinder,
			BroadcastArchiveService broadcastArchiveService,
			ApplicationEventPublisher eventPublisher) {
		this.stationRepository = stationRepository;
		this.programmingService = programmingService;
		this.programmingPolicyRepository = programmingPolicyRepository;
		this.playoutSessionRepository = playoutSessionRepository;
		this.programBlockRepository = programBlockRepository;
		this.programBlockSlotRepository = programBlockSlotRepository;
		this.queueItemRepository = queueItemRepository;
		this.streamEventService = streamEventService;
		this.settingsStore = settingsStore;
		this.assetService = assetService;
		this.clientCapabilitiesService = clientCapabilitiesService;
		this.scriptGenerationService = scriptGenerationService;
		this.playHistoryService = playHistoryService;
		this.letterSegmentBinder = letterSegmentBinder;
		this.broadcastArchiveService = broadcastArchiveService;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	public ClientCapabilitiesResponse registerCapabilities(ClientCapabilitiesRequest request, String correlationId) {
		return clientCapabilitiesService.register(request, correlationId);
	}

	@Transactional
	public TuneResponse tune(TuneRequest request, String correlationId) {
		var station = stationRepository.findById(request.stationId())
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "指定された局が見つかりません。", Map.of("stationId", request.stationId())));
		if (!station.isActive()) {
			throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "指定された局は無効化されています。", Map.of("stationId", request.stationId()));
		}
		stopActiveSessionBeforeRetune();

		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setId(nextId("playout"));
		session.setStationId(request.stationId());
		session.setRequestedBy(request.requestedBy());
		session.setResumePlayback(request.resumePlayback());
		session.setState(PlayoutState.PREPARING);
		session.setBufferReadyCount(0);
		session.setCorrelationId(correlationId);
		session = playoutSessionRepository.save(session);
		emitSessionEvents(session.getId());
		requestQueueWarmup(session.getId());
		return new TuneResponse(session.getId(), session.getStationId(), PlayoutState.PREPARING, true, correlationId);
	}

	@Transactional
	public RadioStatusResponse play() {
		String sessionId = getLatestSessionOrThrow().getId();
		return withSessionLock(sessionId, () -> playLocked(sessionId));
	}

	private RadioStatusResponse playLocked(String sessionId) {
		PlayoutSessionEntity session = playoutSessionRepository.findById(sessionId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "再生セッションが見つかりません。", Map.of("sessionId", sessionId)));
		List<QueueItemEntity> items = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId());
		QueueItemEntity item = items.stream()
				.filter(candidate -> candidate.getStatus() == QueueItemStatus.PLAYING)
				.findFirst()
				.orElse(null);
		if (item == null) {
			item = items.stream()
					.filter(candidate -> candidate.getStatus() == QueueItemStatus.READY)
					.findFirst()
					.orElse(null);
		}
		if (item == null) {
			if (session.getState() == PlayoutState.STOPPED) {
				session.setState(PlayoutState.PREPARING);
				playoutSessionRepository.save(session);
			}
			requestQueueWarmup(session.getId());
			throw new ApiException(HttpStatus.CONFLICT, "QUEUE_NOT_READY", "再生可能なセグメントがまだありません。", Map.of("sessionId", session.getId()));
		}
		transitionToPlaying(session, item.getId());
		refreshSessionState(session);
		requestQueueRefill(session.getId());
		emitSessionEvents(session.getId());
		return getStatus();
	}

	@Transactional
	public RadioStatusResponse stop() {
		String sessionId = getLatestSessionOrThrow().getId();
		return withSessionLock(sessionId, () -> stopLocked(sessionId));
	}

	private RadioStatusResponse stopLocked(String sessionId) {
		PlayoutSessionEntity session = playoutSessionRepository.findById(sessionId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "再生セッションが見つかりません。", Map.of("sessionId", sessionId)));
		QueueItemEntity currentItem = getCurrentQueueItem(session);
		stopPlayback(session);
		if (currentItem != null) {
			playHistoryService.record(session, currentItem, PlayHistoryResultStatus.STOPPED);
		}
		refreshSessionState(session);
		emitSessionEvents(session.getId());
		return getStatus();
	}

	@Transactional(readOnly = true)
	public RadioStatusResponse getStatus() {
		Optional<PlayoutSessionEntity> current = playoutSessionRepository.findFirstByOrderByStartedAtDesc();
		if (current.isEmpty()) {
			return RadioStatusResponse.idle();
		}
		PlayoutSessionEntity session = current.get();
		String templateId = null;
		String title = null;
		if (session.getCurrentProgramBlockId() != null) {
			var block = programBlockRepository.findById(session.getCurrentProgramBlockId()).orElse(null);
			if (block != null) {
				templateId = block.getProgramTemplateId();
				title = block.getTitle();
			}
		}
		return new RadioStatusResponse(
				session.getId(),
				session.getStationId(),
				session.getCurrentProgramBlockId(),
				templateId,
				title,
				session.getState(),
				session.getCurrentQueueItemId(),
				session.getBufferReadyCount(),
				session.getDegradedReason() != null,
				session.getUpdatedAt(),
				session.getCorrelationId());
	}

	@Transactional(readOnly = true)
	public QueueSnapshotResponse getQueue() {
		PlayoutSessionEntity session = getLatestSessionOrThrow();
		return toQueueSnapshot(session.getId());
	}

	@Transactional(readOnly = true)
	public ProgramBlockResponse getProgram() {
		PlayoutSessionEntity session = getLatestSessionOrThrow();
		String programBlockId = session.getCurrentProgramBlockId();
		if (programBlockId == null || programBlockId.isBlank()) {
			throw new ApiException(
					HttpStatus.NOT_FOUND,
					"PROGRAM_NOT_READY",
					"番組は現在準備中です。",
					Map.of("sessionId", session.getId()));
		}
		ProgramBlockEntity block = programBlockRepository.findById(programBlockId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "現在の番組 block が見つかりません。", Map.of("sessionId", session.getId())));
		List<ProgramBlockSlotEntity> slots = programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(block.getId());
		int remaining = (int) slots.stream()
				.filter(slot -> slot.getStatus() != ProgramBlockSlotStatus.DONE && slot.getStatus() != ProgramBlockSlotStatus.SKIPPED)
				.count();
		return new ProgramBlockResponse(
				block.getId(),
				block.getStationId(),
				block.getProgramTemplateId(),
				block.getProgramTemplateVersion(),
				block.getTitle(),
				block.getStatus(),
				block.getPlannedDurationMs(),
				remaining,
				block.getStartedAt(),
				slots.stream()
						.map(slot -> new ProgramBlockSlotResponse(
								slot.getId(),
								slot.getId(),
								slot.getRole(),
								slot.getConstraintMode(),
								slot.getResolvedSegmentType().name(),
								slot.getTargetDurationMs(),
								slot.getStatus().name(),
								slot.getSlotContext(),
								buildTitle(slot)))
						.toList(),
				session.getCorrelationId());
	}

	@Transactional(readOnly = true)
	public QueueItemResponse getNextSegment() {
		PlayoutSessionEntity session = getLatestSessionOrThrow();
		QueueItemEntity item = queueItemRepository.findTopBySessionIdAndStatusOrderBySequenceNoAsc(session.getId(), QueueItemStatus.READY)
				.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "QUEUE_NOT_READY", "次のセグメントはまだ生成されていません。", Map.of("sessionId", session.getId())));
		return toQueueItem(item);
	}

	@Transactional(readOnly = true)
	public SpeechDirectiveResponse getNextSpeechDirective(String clientId) {
		PlayoutSessionEntity session = getLatestSessionOrThrow();
		QueueItemEntity item = queueItemRepository.findTopBySessionIdAndStatusOrderBySequenceNoAsc(session.getId(), QueueItemStatus.READY)
				.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "QUEUE_NOT_READY", "次のセグメントはまだ生成されていません。", Map.of("sessionId", session.getId())));
		return scriptGenerationService.resolveDirective(session, item, clientId);
	}

	@Transactional
	public void recordPlaybackEvent(PlaybackEventRequest request) {
		withSessionLock(request.sessionId(), () -> {
			recordPlaybackEventLocked(request);
			return null;
		});
	}

	private void recordPlaybackEventLocked(PlaybackEventRequest request) {
		PlayoutSessionEntity session = playoutSessionRepository.findById(request.sessionId())
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "再生セッションが見つかりません。", Map.of("sessionId", request.sessionId())));
		QueueItemEntity item = queueItemRepository.findById(request.itemId())
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "QueueItem が見つかりません。", Map.of("itemId", request.itemId())));
		assertPlaybackEventTargetsSession(session, item);
		assertPlaybackEventTransition(request, session, item);
		switch (request.eventType()) {
			case SEGMENT_STARTED -> {
				transitionToPlaying(session, item.getId());
				requestQueueRefill(session.getId());
			}
			case SEGMENT_ENDED -> {
				item.setStatus(QueueItemStatus.DONE);
				if (item.getId().equals(session.getCurrentQueueItemId())) {
					session.setCurrentQueueItemId(null);
				}
				markSlotDone(item.getProgramSlotId());
				queueItemRepository.save(item);
				playHistoryService.record(session, item, PlayHistoryResultStatus.DONE);
				requestQueueRefill(session.getId());
			}
			case SEGMENT_ERROR -> {
				item.setStatus(QueueItemStatus.FAILED);
				item.setAssetBanned(true);
				if (item.getId().equals(session.getCurrentQueueItemId())) {
					session.setCurrentQueueItemId(null);
				}
				markSlotSkipped(item.getProgramSlotId());
				session.setState(PlayoutState.DEGRADED);
				session.setDegradedReason("SEGMENT_ERROR");
				queueItemRepository.save(item);
				playHistoryService.record(session, item, PlayHistoryResultStatus.FAILED);
				requestQueueRefill(session.getId());
			}
			case PLAYBACK_STOPPED -> {
				stopPlayback(session);
				playHistoryService.record(session, item, PlayHistoryResultStatus.STOPPED);
			}
			default -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "未対応の playback event です。", Map.of("eventType", request.eventType()));
		}
		refreshSessionState(session);
		emitSessionEvents(session.getId());
	}

	@Transactional(readOnly = true)
	public HealthResponse health() {
		Optional<PlayoutSessionEntity> latest = playoutSessionRepository.findFirstByOrderByStartedAtDesc();
		return new HealthResponse(
				"UP",
				Instant.now(),
				stationRepository.count(),
				playoutSessionRepository.count(),
				queueItemRepository.count(),
				streamEventService.latestEventId(),
				latest.map(PlayoutSessionEntity::getId).orElse(null),
				Map.of());
	}

	public byte[] placeholderWav() {
		int sampleRate = 8_000;
		int seconds = 1;
		int dataSize = sampleRate * seconds;
		ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put("RIFF".getBytes());
		buffer.putInt(36 + dataSize);
		buffer.put("WAVEfmt ".getBytes());
		buffer.putInt(16);
		buffer.putShort((short) 1);
		buffer.putShort((short) 1);
		buffer.putInt(sampleRate);
		buffer.putInt(sampleRate);
		buffer.putShort((short) 1);
		buffer.putShort((short) 8);
		buffer.put("data".getBytes());
		buffer.putInt(dataSize);
		for (int i = 0; i < dataSize; i++) {
			buffer.put((byte) 0x80);
		}
		return buffer.array();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void warmupQueue(String sessionId) {
		withSessionLock(sessionId, () -> maintainQueue(sessionId, true));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void refillQueue(String sessionId) {
		withSessionLock(sessionId, () -> maintainQueue(sessionId, false));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PreGenerationResult preGenerateOffAirContent(
			String sessionId,
			String templateId,
			int targetProgramCount,
			boolean includeSpeech,
			boolean includeMusic) {
		return withSessionLock(sessionId, () -> {
			PlayoutSessionEntity session = playoutSessionRepository.findById(sessionId)
					.orElseThrow(() -> new ApiException(
							HttpStatus.NOT_FOUND,
							"NOT_FOUND",
							"事前生成セッションが見つかりません。",
							Map.of("sessionId", sessionId)));
			if (!session.isPreGeneration()) {
				throw new ApiException(
						HttpStatus.CONFLICT,
						"CONFLICT",
						"ライブ再生セッションは手動事前生成に使用できません。",
						Map.of("sessionId", sessionId));
			}
			int programCount = Math.max(1, Math.min(10, targetProgramCount));
			int nextSequence = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(sessionId).size() + 1;
			int materializedSegmentCount = 0;
			int queuedMusicCount = 0;
			OffsetDateTime plannedAt = OffsetDateTime.now();
			for (int programIndex = 0; programIndex < programCount; programIndex++) {
				ResolvedProgramPlan plan = programmingService.resolvePreGenerationPlan(
						session.getStationId(),
						templateId,
						plannedAt);
				ProgramBlockEntity block = createProgramBlock(session, plan, ProgramBlockStatus.PLANNED);
				List<ProgramBlockSlotEntity> blockSlots =
						programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(block.getId());
				List<QueueItemEntity> queueItems = new ArrayList<>();
				for (ProgramBlockSlotEntity blockSlot : blockSlots) {
					QueueItemEntity item = createQueueItem(
							session,
							block,
							blockSlot,
							nextSequence++,
							blockSlots.size());
					blockSlot.setStatus(ProgramBlockSlotStatus.QUEUED);
					queueItems.add(item);
				}
				queueItems = queueItemRepository.saveAll(queueItems);
				queueItemRepository.flush();
				for (QueueItemEntity item : queueItems) {
					if (item.getAssetId() != null && !item.getAssetId().isBlank()) {
						continue;
					}
					if (item.getSegmentType() == SegmentType.MUSIC_AI) {
						if (includeMusic) {
							item.setStatus(QueueItemStatus.GENERATING);
							requestGenerateMusic(item);
							queuedMusicCount++;
						} else {
							item.setStatus(QueueItemStatus.PLANNED);
						}
						continue;
					}
					if (item.getSegmentType() == SegmentType.MUSIC_LOCAL) {
						if (includeMusic) {
							assetService.ensureQueueAudioAsset(item);
						} else {
							item.setStatus(QueueItemStatus.PLANNED);
						}
						continue;
					}
					if (includeSpeech) {
						assetService.ensureQueueAudioAsset(item);
					} else {
						item.setStatus(QueueItemStatus.PLANNED);
					}
				}
				queueItemRepository.saveAll(queueItems);
				programBlockSlotRepository.saveAll(blockSlots);
				materializedSegmentCount += queueItems.size();
				plannedAt = plannedAt.plusNanos((long) plan.plannedDurationMs() * 1_000_000L);
			}
			long readyCount = queueItemRepository.countBySessionIdAndStatus(sessionId, QueueItemStatus.READY);
			session.setBufferReadyCount(Math.toIntExact(Math.min(Integer.MAX_VALUE, readyCount)));
			session.setState(PlayoutState.STOPPED);
			playoutSessionRepository.save(session);
			return new PreGenerationResult(programCount, materializedSegmentCount, queuedMusicCount);
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void synchronizeSessionAfterAsyncUpdate(String sessionId) {
		withSessionLock(sessionId, () -> {
			PlayoutSessionEntity session = playoutSessionRepository.findById(sessionId).orElse(null);
			if (session == null) {
				return;
			}
			SettingsDocument.PlayoutSettings playout = playoutSettings();
			PreGenerationProfile preGeneration = preGenerationProfile(session.getStationId());
			promotePendingMusicGenerations(session, queuePreparationPolicy(session, playout, preGeneration));
			autoStartPlaybackIfRequested(session);
			refreshSessionState(session);
			emitSessionEvents(sessionId);
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void handleAsyncGenerationFailure(String sessionId, String degradedReason) {
		handleAsyncGenerationFailure(sessionId, null, degradedReason);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void handleAsyncGenerationFailure(String sessionId, String queueItemId, String degradedReason) {
		withSessionLock(sessionId, () -> {
			PlayoutSessionEntity session = playoutSessionRepository.findById(sessionId).orElse(null);
			if (session == null) {
				return;
			}
			boolean fallbackApplied = false;
			if (queueItemId != null && !queueItemId.isBlank()) {
				QueueItemEntity latestItem = queueItemRepository.findById(queueItemId).orElse(null);
				fallbackApplied = applyAsyncMusicFailureFallback(latestItem, degradedReason);
				if (!fallbackApplied && latestItem != null) {
					markSlotSkipped(latestItem.getProgramSlotId());
				}
			}
			session.setDegradedReason(degradedReason);
			requestQueueRefill(sessionId);
			autoStartPlaybackIfRequested(session);
			refreshSessionState(session);
			emitSessionEvents(sessionId);
		});
	}

	private boolean applyAsyncMusicFailureFallback(QueueItemEntity item, String degradedReason) {
		if (item == null
				|| item.getSegmentType() != SegmentType.MUSIC_AI
				|| item.getStatus() != QueueItemStatus.GENERATING) {
			return false;
		}
		AssetService.MusicFailureFallback fallback = assetService.prepareMusicFailureFallback(item, degradedReason);
		item.setSegmentType(fallback.segmentType());
		item.setStatus(QueueItemStatus.READY);
		item.setAssetId(fallback.assetId());
		item.setAssetUrl(fallback.assetUrl());
		item.setTitle(fallback.title());
		item.setContentOrigin(fallback.contentOrigin());
		item.setReplayOfPlayHistoryId(null);
		item.setAssetBanned(false);
		queueItemRepository.save(item);
		return true;
	}

	private ProgramBlockEntity createProgramBlock(PlayoutSessionEntity session, ResolvedProgramPlan plan, ProgramBlockStatus status) {
		ProgramBlockEntity block = new ProgramBlockEntity();
		block.setId(nextId("program"));
		block.setStationId(session.getStationId());
		block.setSessionId(session.getId());
		block.setProgramTemplateId(plan.templateId());
		block.setProgramTemplateVersion(plan.templateVersion());
		block.setTitle(plan.title());
		block.setStatus(status);
		block.setPlannedDurationMs(plan.plannedDurationMs());
		block = programBlockRepository.save(block);

		int sequence = 1;
		List<ProgramBlockSlotEntity> blockSlots = new ArrayList<>();
		for (ResolvedSlot slot : plan.slots()) {
			ProgramBlockSlotEntity blockSlot = new ProgramBlockSlotEntity();
			blockSlot.setId(nextId("block-slot"));
			blockSlot.setProgramBlockId(block.getId());
			blockSlot.setTemplateSlotId(slot.slotId());
			blockSlot.setSequenceNo(sequence++);
			blockSlot.setRole(slot.role());
			blockSlot.setConstraintMode(slot.constraintMode());
			blockSlot.setResolvedSegmentType(slot.resolvedSegmentType());
			blockSlot.setStatus(ProgramBlockSlotStatus.PLANNED);
			blockSlot.setTargetDurationMs(slot.targetDurationMs());
			blockSlot.setSlotContext(new LinkedHashMap<>());
			blockSlots.add(blockSlot);
		}
		programBlockSlotRepository.saveAll(blockSlots);
		return block;
	}

	private void maintainQueue(String sessionId, boolean allowInitialization) {
		SettingsDocument.PlayoutSettings playout = playoutSettings();
		PlayoutSessionEntity session = playoutSessionRepository.findById(sessionId).orElse(null);
		if (session == null || shouldSkipQueueMaintenance(session)) {
			return;
		}
		PreGenerationProfile preGeneration = preGenerationProfile(session.getStationId());
		if (session.getCurrentProgramBlockId() == null) {
			if (!allowInitialization) {
				return;
			}
			ResolvedProgramPlan plan = programmingService.resolveCurrentPlan(session.getStationId(), OffsetDateTime.now());
			ProgramBlockEntity block = createProgramBlock(session, plan, ProgramBlockStatus.ACTIVE);
			session.setCurrentProgramBlockId(block.getId());
			if (plan.fallbackApplied()) {
				session.setState(PlayoutState.DEGRADED);
				session.setDegradedReason("LEGACY_RATIO");
			}
			playoutSessionRepository.save(session);
		}
		ensureBuffer(session, playout, preGeneration);
		autoStartPlaybackIfRequested(session);
		refreshSessionState(session);
		emitSessionEvents(sessionId);
	}

	private void materializeInitialQueue(
			PlayoutSessionEntity session,
			ProgramBlockEntity block,
			ResolvedProgramPlan plan,
			QueuePreparationPolicy queuePreparationPolicy) {
		List<ProgramBlockSlotEntity> blockSlots = programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(block.getId());
		int sequenceStart = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId()).size() + 1;
		List<QueueItemEntity> queueItems = new ArrayList<>();
		for (int i = 0; i < Math.min(queuePreparationPolicy.targetReadyCount(), blockSlots.size()); i++) {
			ProgramBlockSlotEntity blockSlot = blockSlots.get(i);
			queueItems.add(createQueueItem(session, block, blockSlot, sequenceStart++, blockSlots.size()));
			blockSlot.setStatus(ProgramBlockSlotStatus.QUEUED);
			if (queuePreparationPolicy.hasReachedPreparedDurationLimit(totalReadyDuration(queueItems))) {
				break;
			}
		}
		queueItemRepository.saveAll(queueItems);
		queueItemRepository.flush();
		letterSegmentBinder.bindPendingSegments(session.getId());
		prefetchPendingSpokenAssets(session, queuePreparationPolicy);
		promotePendingMusicGenerations(session, queuePreparationPolicy);
		programBlockSlotRepository.saveAll(blockSlots);
		if (plan.fallbackApplied()) {
			session.setState(PlayoutState.DEGRADED);
			session.setDegradedReason("LEGACY_RATIO");
		}
	}

	private QueueItemEntity createQueueItem(PlayoutSessionEntity session, ProgramBlockEntity block, ProgramBlockSlotEntity blockSlot, int sequenceNo, int totalBlockSlots) {
		QueueItemEntity entity = new QueueItemEntity();
		entity.setId(nextId("queue"));
		entity.setSessionId(session.getId());
		entity.setSequenceNo(sequenceNo);
		entity.setSegmentType(blockSlot.getResolvedSegmentType());
		entity.setStatus(blockSlot.getResolvedSegmentType() == SegmentType.MUSIC_AI ? QueueItemStatus.PLANNED : QueueItemStatus.READY);
		entity.setProgramBlockId(block.getId());
		entity.setProgramSlotId(blockSlot.getId());
		entity.setSlotRole(blockSlot.getRole());
		entity.setTitle(buildTitle(blockSlot));
		entity.setPlaybackMode(PlaybackMode.SERVER_AUDIO);
		entity.setSpeechDirectiveId("sd-" + entity.getId());
		entity.setContentOrigin("LIVE_GEN");
		entity.setDurationMs(blockSlot.getTargetDurationMs());
		entity.setCorrelationId(session.getCorrelationId());
		applyArchiveReplay(session, block, blockSlot, entity, totalBlockSlots);
		return entity;
	}

	private void applyArchiveReplay(PlayoutSessionEntity session, ProgramBlockEntity block, ProgramBlockSlotEntity blockSlot, QueueItemEntity item, int totalBlockSlots) {
		if (blockSlot.getConstraintMode() != com.seedshiftradio.domain.ConstraintMode.SOFT
				|| item.getSegmentType() == SegmentType.LETTER) {
			return;
		}
		broadcastArchiveService.findReplayCandidate(session.getStationId(), item.getSegmentType(), block.getId(), totalBlockSlots).ifPresent(archive -> {
			item.setAssetId(archive.getPrimaryAssetId());
			item.setAssetUrl("/api/assets/audio/" + archive.getPrimaryAssetId() + ".wav");
			item.setContentOrigin("ARCHIVE_REPLAY");
			item.setReplayOfPlayHistoryId(archive.getSourcePlayHistoryId());
			item.setTitle(archive.getTitle());
			item.setStatus(QueueItemStatus.READY);
		});
	}

	private String buildTitle(ProgramBlockSlotEntity slot) {
		return switch (slot.getRole()) {
			case OPENING -> "オープニング";
			case TOPIC -> "トーク";
			case LETTER -> "レター";
			case MUSIC_BREAK -> "ミュージックブレイク";
			case ENDING -> "エンディング";
		};
	}

	private void ensureBuffer(
			PlayoutSessionEntity session,
			SettingsDocument.PlayoutSettings playout,
			PreGenerationProfile preGeneration) {
		List<QueueItemEntity> items = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId());
		long readyCount = items.stream().filter(item -> item.getStatus() == QueueItemStatus.READY).count();
		int readyDuration = items.stream()
				.filter(item -> item.getStatus() == QueueItemStatus.READY)
				.mapToInt(QueueItemEntity::getDurationMs)
				.sum();
		QueuePreparationPolicy queuePreparationPolicy = queuePreparationPolicy(session, playout, preGeneration, items);
		ProgramBlockEntity currentBlock = getCurrentProgramBlock(session);
		ProgramBlockEntity latestBlock = planNextProgramBlockIfNeeded(session, currentBlock, queuePreparationPolicy);
		if (queuePreparationPolicy.hasReachedSafetyBuffer(readyCount, readyDuration)) {
			prefetchPendingSpokenAssets(session, queuePreparationPolicy);
			completeAndAdvanceProgramBlock(session, items, currentBlock, latestBlock);
			return;
		}
		ProgramBlockEntity refillBlock = resolveRefillBlock(currentBlock, latestBlock);
		List<ProgramBlockSlotEntity> blockSlots = refillBlock == null
				? List.of()
				: programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(refillBlock.getId());
		int nextSequence = items.size() + 1;
		List<QueueItemEntity> additions = new ArrayList<>();
		List<ProgramBlockSlotEntity> changedSlots = new ArrayList<>();
		for (ProgramBlockSlotEntity blockSlot : blockSlots) {
			if (blockSlot.getStatus() == ProgramBlockSlotStatus.PLANNED) {
				QueueItemEntity item = createQueueItem(session, refillBlock, blockSlot, nextSequence++, blockSlots.size());
				additions.add(item);
				blockSlot.setStatus(ProgramBlockSlotStatus.QUEUED);
				changedSlots.add(blockSlot);
				if (item.getStatus() == QueueItemStatus.READY) {
					readyCount++;
					readyDuration += blockSlot.getTargetDurationMs();
				}
			}
			if (queuePreparationPolicy.hasReachedTargetBuffer(readyCount, readyDuration)) {
				break;
			}
			if (queuePreparationPolicy.hasReachedPreparedDurationLimit(readyDuration)) {
				break;
			}
		}
		if (additions.isEmpty() && readyCount == 0) {
			additions.add(createFallbackQueueItem(session, nextSequence));
			session.setState(PlayoutState.DEGRADED);
			session.setDegradedReason("LEGACY_RATIO");
			streamEventService.publish("buffer.warning", new BufferWarningPayload(session.getId(), readyCount, Instant.now()));
		}
		if (!additions.isEmpty()) {
			queueItemRepository.saveAll(additions);
			queueItemRepository.flush();
			letterSegmentBinder.bindPendingSegments(session.getId());
		}
		QueuePreparationPolicy updatedQueuePreparationPolicy = queuePreparationPolicy(session, playout, preGeneration);
		prefetchPendingSpokenAssets(session, updatedQueuePreparationPolicy);
		promotePendingMusicGenerations(session, updatedQueuePreparationPolicy);
		if (!changedSlots.isEmpty()) {
			programBlockSlotRepository.saveAll(changedSlots);
		}
		completeAndAdvanceProgramBlock(
				session,
				queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId()),
				currentBlock,
				latestBlock);
	}

	private ProgramBlockEntity getCurrentProgramBlock(PlayoutSessionEntity session) {
		if (session.getCurrentProgramBlockId() == null) {
			return null;
		}
		return programBlockRepository.findById(session.getCurrentProgramBlockId()).orElse(null);
	}

	private ProgramBlockEntity planNextProgramBlockIfNeeded(
			PlayoutSessionEntity session,
			ProgramBlockEntity currentBlock,
			QueuePreparationPolicy queuePreparationPolicy) {
		ProgramBlockEntity latestBlock = programBlockRepository.findTopBySessionIdOrderByStartedAtDesc(session.getId()).orElse(currentBlock);
		if (currentBlock == null) {
			return latestBlock;
		}
		if (latestBlock != null && !latestBlock.getId().equals(currentBlock.getId())) {
			return latestBlock;
		}
		long preparedBlockCount = programBlockRepository.findBySessionIdOrderByStartedAtAsc(session.getId()).stream()
				.filter(block -> block.getStatus() == ProgramBlockStatus.ACTIVE || block.getStatus() == ProgramBlockStatus.PLANNED)
				.count();
		if (preparedBlockCount >= queuePreparationPolicy.maxPreparedBlocks()) {
			return latestBlock;
		}
		long remainingSlotCount = programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(currentBlock.getId()).stream()
				.filter(slot -> slot.getStatus() != ProgramBlockSlotStatus.DONE && slot.getStatus() != ProgramBlockSlotStatus.SKIPPED)
				.count();
		if (queuePreparationPolicy.shouldDeferNextBlockPlanning(remainingSlotCount)) {
			return latestBlock;
		}
		ResolvedProgramPlan plan = programmingService.resolveCurrentPlan(session.getStationId(), OffsetDateTime.now());
		ProgramBlockEntity nextBlock = createProgramBlock(session, plan, ProgramBlockStatus.PLANNED);
		if (plan.fallbackApplied()) {
			session.setState(PlayoutState.DEGRADED);
			session.setDegradedReason("LEGACY_RATIO");
		}
		return nextBlock;
	}

	private ProgramBlockEntity resolveRefillBlock(ProgramBlockEntity currentBlock, ProgramBlockEntity latestBlock) {
		if (currentBlock != null && hasPlannedSlots(currentBlock.getId())) {
			return currentBlock;
		}
		if (latestBlock != null && hasPlannedSlots(latestBlock.getId())) {
			return latestBlock;
		}
		return null;
	}

	private boolean hasPlannedSlots(String programBlockId) {
		return programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(programBlockId).stream()
				.anyMatch(slot -> slot.getStatus() == ProgramBlockSlotStatus.PLANNED);
	}

	private PreGenerationProfile preGenerationProfile(String stationId) {
		return programmingPolicyRepository.findByStationId(stationId)
				.map(policy -> ProgrammingPolicyProfileSupport.toPreGenerationProfile(policy.getPreGenerationPolicy()))
				.orElseGet(ProgrammingPolicyProfileSupport::defaultPreGenerationProfile);
	}

	private QueuePreparationPolicy queuePreparationPolicy(
			PlayoutSessionEntity session,
			SettingsDocument.PlayoutSettings playout,
			PreGenerationProfile preGeneration) {
		List<QueueItemEntity> queueItems = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId());
		return queuePreparationPolicy(session, playout, preGeneration, queueItems);
	}

	private QueuePreparationPolicy queuePreparationPolicy(
			PlayoutSessionEntity session,
			SettingsDocument.PlayoutSettings playout,
			PreGenerationProfile preGeneration,
			List<QueueItemEntity> queueItems) {
		return QueuePreparationPolicy.resolve(playout, preGeneration, session, queueItems);
	}

	private void completeAndAdvanceProgramBlock(
			PlayoutSessionEntity session,
			List<QueueItemEntity> items,
			ProgramBlockEntity currentBlock,
			ProgramBlockEntity latestBlock) {
		if (currentBlock == null) {
			return;
		}
		boolean currentBlockCompleted = programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(currentBlock.getId()).stream()
				.allMatch(slot -> slot.getStatus() == ProgramBlockSlotStatus.DONE || slot.getStatus() == ProgramBlockSlotStatus.SKIPPED);
		if (!currentBlockCompleted) {
			return;
		}
		boolean hasOutstandingQueueItem = items.stream()
				.filter(item -> currentBlock.getId().equals(item.getProgramBlockId()))
				.anyMatch(item -> item.getStatus() != QueueItemStatus.DONE
						&& item.getStatus() != QueueItemStatus.FAILED
						&& item.getStatus() != QueueItemStatus.SKIPPED);
		if (hasOutstandingQueueItem) {
			return;
		}
		if (currentBlock.getStatus() != ProgramBlockStatus.DONE) {
			currentBlock.setStatus(ProgramBlockStatus.DONE);
			currentBlock.setEndedAt(Instant.now());
			programBlockRepository.save(currentBlock);
		}
		if (latestBlock != null && !latestBlock.getId().equals(currentBlock.getId())) {
			if (latestBlock.getStatus() != ProgramBlockStatus.ACTIVE) {
				latestBlock.setStatus(ProgramBlockStatus.ACTIVE);
				programBlockRepository.save(latestBlock);
			}
			session.setCurrentProgramBlockId(latestBlock.getId());
		}
	}

	private void transitionToPlaying(PlayoutSessionEntity session, String itemId) {
		List<QueueItemEntity> items = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId());
		List<QueueItemEntity> changedItems = new ArrayList<>();
		QueueItemEntity target = null;
		for (QueueItemEntity existing : items) {
			if (existing.getId().equals(itemId)) {
				target = existing;
				if (existing.getStatus() != QueueItemStatus.PLAYING) {
					existing.setStatus(QueueItemStatus.PLAYING);
					changedItems.add(existing);
				}
				continue;
			}
			if (existing.getStatus() == QueueItemStatus.PLAYING) {
				existing.setStatus(QueueItemStatus.READY);
				changedItems.add(existing);
			}
		}
		if (target == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "QueueItem が見つかりません。", Map.of("itemId", itemId));
		}
		if (!changedItems.isEmpty()) {
			queueItemRepository.saveAll(changedItems);
		}
		session.setCurrentQueueItemId(target.getId());
		if (isRecoveryCandidate(target)) {
			session.setDegradedReason(null);
		}
		session.setState(session.getDegradedReason() == null ? PlayoutState.PLAYING : PlayoutState.DEGRADED);
	}

	private void requestQueueWarmup(String sessionId) {
		eventPublisher.publishEvent(new QueueWarmupRequested(sessionId));
	}

	private void requestQueueRefill(String sessionId) {
		eventPublisher.publishEvent(new QueueRefillRequested(sessionId));
	}

	private void promotePendingMusicGenerations(PlayoutSessionEntity session, QueuePreparationPolicy queuePreparationPolicy) {
		List<QueueItemEntity> queueItems = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId());
		int activeMusicCount = (int) queueItems.stream()
				.filter(item -> item.getSegmentType() == SegmentType.MUSIC_AI)
				.filter(item -> item.getStatus() == QueueItemStatus.GENERATING
						|| item.getStatus() == QueueItemStatus.READY
						|| item.getStatus() == QueueItemStatus.PLAYING)
				.count();
		int preparedFutureBlockMusicCount = (int) queueItems.stream()
				.filter(item -> item.getSegmentType() == SegmentType.MUSIC_AI)
				.filter(item -> item.getStatus() == QueueItemStatus.GENERATING
						|| item.getStatus() == QueueItemStatus.READY
						|| item.getStatus() == QueueItemStatus.PLAYING)
				.filter(item -> isFutureBlockMusicCandidate(session, item))
				.count();
		for (QueueItemEntity item : queueItems) {
			if (activeMusicCount >= queuePreparationPolicy.musicAheadCount()) {
				return;
			}
			if (item.getSegmentType() != SegmentType.MUSIC_AI
					|| item.getStatus() != QueueItemStatus.PLANNED
					|| (item.getAssetId() != null && !item.getAssetId().isBlank())
					|| !queuePreparationPolicy.allowsMusicGeneration(session, item, preparedFutureBlockMusicCount)) {
				continue;
			}
			item.setStatus(QueueItemStatus.GENERATING);
			queueItemRepository.save(item);
			requestGenerateMusic(item);
			activeMusicCount++;
			if (isFutureBlockMusicCandidate(session, item)) {
				preparedFutureBlockMusicCount++;
			}
		}
	}

	private boolean isFutureBlockMusicCandidate(PlayoutSessionEntity session, QueueItemEntity item) {
		String currentProgramBlockId = session.getCurrentProgramBlockId();
		return currentProgramBlockId != null
				&& item.getProgramBlockId() != null
				&& !currentProgramBlockId.equals(item.getProgramBlockId());
	}

	private void prefetchPendingSpokenAssets(PlayoutSessionEntity session, QueuePreparationPolicy queuePreparationPolicy) {
		List<QueueItemEntity> queueItems = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId());
		List<QueueItemEntity> changedItems = new ArrayList<>();
		int preparedAudioCount = 0;
		int preparedScriptCount = 0;
		int preparedCurrentBlockLetterCount = 0;
		for (QueueItemEntity item : queueItems) {
			if (item.getStatus() == QueueItemStatus.READY
					&& item.getSegmentType() == SegmentType.MUSIC_LOCAL
					&& !hasPreparedAudioAsset(item)) {
				assetService.ensureQueueAudioAsset(item);
				changedItems.add(item);
				continue;
			}
			if (!isFutureSpokenCandidate(item)
					|| !queuePreparationPolicy.allowsFutureSpokenPrefetch(session, item, preparedCurrentBlockLetterCount)) {
				continue;
			}
			if (preparedAudioCount >= queuePreparationPolicy.ttsAheadCount()
					&& preparedScriptCount >= queuePreparationPolicy.scriptAheadCount()) {
				break;
			}
			if (hasPreparedAudioAsset(item)) {
				preparedAudioCount++;
				preparedScriptCount++;
				preparedCurrentBlockLetterCount += currentBlockLetterPrefetchIncrement(item);
				continue;
			}
			if (preparedAudioCount < queuePreparationPolicy.ttsAheadCount()) {
				assetService.ensureQueueAudioAsset(item);
				changedItems.add(item);
				preparedAudioCount++;
				preparedScriptCount++;
				preparedCurrentBlockLetterCount += currentBlockLetterPrefetchIncrement(item);
				continue;
			}
			if (preparedScriptCount < queuePreparationPolicy.scriptAheadCount()) {
				scriptGenerationService.ensureScriptAsset(item);
				preparedScriptCount++;
				preparedCurrentBlockLetterCount += currentBlockLetterPrefetchIncrement(item);
			}
		}
		if (!changedItems.isEmpty()) {
			queueItemRepository.saveAll(changedItems);
		}
	}

	private int currentBlockLetterPrefetchIncrement(QueueItemEntity item) {
		return item.getSegmentType() == SegmentType.LETTER ? 1 : 0;
	}

	private boolean isFutureSpokenCandidate(QueueItemEntity item) {
		return item.getStatus() == QueueItemStatus.READY
				&& item.getSegmentType() != SegmentType.MUSIC_AI
				&& item.getSegmentType() != SegmentType.MUSIC_LOCAL;
	}

	private boolean hasPreparedAudioAsset(QueueItemEntity item) {
		return item.getAssetId() != null && !item.getAssetId().isBlank();
	}

	private void autoStartPlaybackIfRequested(PlayoutSessionEntity session) {
		if (!session.isResumePlayback()
				|| session.getCurrentQueueItemId() != null
				|| session.getState() == PlayoutState.STOPPED
				|| session.getState() == PlayoutState.ERROR) {
			return;
		}
		QueueItemEntity readyItem = queueItemRepository.findTopBySessionIdAndStatusOrderBySequenceNoAsc(session.getId(), QueueItemStatus.READY)
				.orElse(null);
		if (readyItem == null) {
			return;
		}
		transitionToPlaying(session, readyItem.getId());
		requestQueueRefill(session.getId());
	}

	private void stopPlayback(PlayoutSessionEntity session) {
		List<QueueItemEntity> items = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId());
		List<QueueItemEntity> changedItems = new ArrayList<>();
		for (QueueItemEntity item : items) {
			if (item.getStatus() == QueueItemStatus.PLAYING) {
				item.setStatus(QueueItemStatus.READY);
				changedItems.add(item);
			}
		}
		if (!changedItems.isEmpty()) {
			queueItemRepository.saveAll(changedItems);
		}
		session.setCurrentQueueItemId(null);
		session.setState(PlayoutState.STOPPED);
	}

	private QueueItemEntity createFallbackQueueItem(PlayoutSessionEntity session, int sequenceNo) {
		QueueItemEntity entity = new QueueItemEntity();
		entity.setId(nextId("queue"));
		entity.setSessionId(session.getId());
		entity.setSequenceNo(sequenceNo);
		entity.setSegmentType(SegmentType.JINGLE);
		entity.setStatus(QueueItemStatus.READY);
		entity.setSlotRole(SlotRole.ENDING);
		entity.setTitle("フォールバックジングル");
		entity.setPlaybackMode(PlaybackMode.SERVER_AUDIO);
		entity.setSpeechDirectiveId("sd-" + entity.getId());
		entity.setContentOrigin("PLACEHOLDER");
		entity.setDurationMs(15_000);
		entity.setCorrelationId(session.getCorrelationId());
		return entity;
	}

	private void markSlotDone(String programSlotId) {
		if (programSlotId == null) {
			return;
		}
		ProgramBlockSlotEntity blockSlot = programBlockSlotRepository.findById(programSlotId).orElse(null);
		if (blockSlot != null) {
			blockSlot.setStatus(ProgramBlockSlotStatus.DONE);
			programBlockSlotRepository.save(blockSlot);
		}
	}

	private void markSlotSkipped(String programSlotId) {
		if (programSlotId == null) {
			return;
		}
		ProgramBlockSlotEntity blockSlot = programBlockSlotRepository.findById(programSlotId).orElse(null);
		if (blockSlot != null && blockSlot.getStatus() != ProgramBlockSlotStatus.DONE) {
			blockSlot.setStatus(ProgramBlockSlotStatus.SKIPPED);
			programBlockSlotRepository.save(blockSlot);
		}
	}

	private void assertPlaybackEventTargetsSession(PlayoutSessionEntity session, QueueItemEntity item) {
		if (!session.getId().equals(item.getSessionId())) {
			throw new ApiException(
					HttpStatus.CONFLICT,
					"CONFLICT",
					"指定された QueueItem は再生セッションに属していません。",
					Map.of(
							"sessionId", session.getId(),
							"itemId", item.getId(),
							"itemSessionId", item.getSessionId()));
		}
	}

	private void assertPlaybackEventTransition(PlaybackEventRequest request, PlayoutSessionEntity session, QueueItemEntity item) {
		switch (request.eventType()) {
			case SEGMENT_STARTED -> {
				boolean alreadyCurrent = item.getStatus() == QueueItemStatus.PLAYING && item.getId().equals(session.getCurrentQueueItemId());
				if (item.getStatus() != QueueItemStatus.READY && !alreadyCurrent) {
					throw invalidPlaybackTransition(request.eventType(), item);
				}
			}
			case SEGMENT_ENDED -> {
				if (item.getStatus() != QueueItemStatus.PLAYING || !item.getId().equals(session.getCurrentQueueItemId())) {
					throw invalidPlaybackTransition(request.eventType(), item);
				}
			}
			case SEGMENT_ERROR -> {
				boolean currentPlaying = item.getStatus() == QueueItemStatus.PLAYING && item.getId().equals(session.getCurrentQueueItemId());
				if (item.getStatus() != QueueItemStatus.READY && !currentPlaying) {
					throw invalidPlaybackTransition(request.eventType(), item);
				}
			}
			case PLAYBACK_STOPPED -> {
				if (item.getStatus() != QueueItemStatus.PLAYING || !item.getId().equals(session.getCurrentQueueItemId())) {
					throw invalidPlaybackTransition(request.eventType(), item);
				}
			}
			default -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "未対応の playback event です。", Map.of("eventType", request.eventType()));
		}
	}

	private ApiException invalidPlaybackTransition(PlaybackEventType eventType, QueueItemEntity item) {
		return new ApiException(
				HttpStatus.CONFLICT,
				"CONFLICT",
				"指定された playback event は現在の QueueItem 状態では受け付けられません。",
				Map.of(
						"eventType", eventType.name(),
						"itemId", item.getId(),
						"status", item.getStatus().name()));
	}

	private boolean isRecoveryCandidate(QueueItemEntity item) {
		return item.getProgramSlotId() != null && !item.isAssetBanned();
	}

	private void stopActiveSessionBeforeRetune() {
		playoutSessionRepository.findFirstByOrderByStartedAtDesc().ifPresent(previousSession -> {
			QueueItemEntity currentItem = getCurrentQueueItem(previousSession);
			stopPlayback(previousSession);
			if (currentItem != null) {
				playHistoryService.record(previousSession, currentItem, PlayHistoryResultStatus.STOPPED);
			}
			refreshSessionState(previousSession);
			emitSessionEvents(previousSession.getId());
		});
	}

	private boolean shouldSkipQueueMaintenance(PlayoutSessionEntity session) {
		if (session.getState() == PlayoutState.STOPPED || session.getState() == PlayoutState.ERROR) {
			return true;
		}
		return playoutSessionRepository.findFirstByOrderByStartedAtDesc()
				.map(latest -> !latest.getId().equals(session.getId()))
				.orElse(false);
	}

	private void withSessionLock(String sessionId, Runnable action) {
		withSessionLock(sessionId, () -> {
			action.run();
			return null;
		});
	}

	private <T> T withSessionLock(String sessionId, Supplier<T> action) {
		ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, ignored -> new ReentrantLock());
		lock.lock();
		try {
			return action.get();
		} finally {
			lock.unlock();
			if (!lock.hasQueuedThreads()) {
				sessionLocks.remove(sessionId, lock);
			}
		}
	}

	private void refreshSessionState(PlayoutSessionEntity session) {
		long readyCount = countReadyItems(session.getId());
		session.setBufferReadyCount((int) readyCount);
		if (session.getState() != PlayoutState.STOPPED && session.getState() != PlayoutState.ERROR) {
			if (session.getCurrentQueueItemId() != null) {
				session.setState(session.getDegradedReason() == null ? PlayoutState.PLAYING : PlayoutState.DEGRADED);
			} else {
				session.setState(PlayoutState.PREPARING);
			}
		}
		playoutSessionRepository.save(session);
	}

	private void emitSessionEvents(String sessionId) {
		PlayoutSessionEntity session = playoutSessionRepository.findById(sessionId).orElseThrow();
		streamEventService.publish("radio.status.changed", getStatus());
		streamEventService.publish("queue.updated", toQueueSnapshot(sessionId));
		if (session.getCurrentProgramBlockId() != null) {
			streamEventService.publish("program.changed", getProgram());
		}
		emitSubtitleEvent(session);
	}

	private void emitSubtitleEvent(PlayoutSessionEntity session) {
		QueueItemEntity currentItem = getCurrentQueueItem(session);
		if (currentItem == null) {
			streamEventService.publish("subtitle.updated", new SubtitlePayload(session.getId(), null, null, "", Instant.now()));
			return;
		}
		SpeechDirectiveResponse directive = scriptGenerationService.resolveDirective(session, currentItem, null);
		String text = directive.normalizedText() != null && !directive.normalizedText().isBlank()
				? directive.normalizedText()
				: directive.text();
		streamEventService.publish(
				"subtitle.updated",
				new SubtitlePayload(
						session.getId(),
						currentItem.getId(),
						directive.id(),
						text == null ? "" : text,
						Instant.now()));
	}

	private QueueSnapshotResponse toQueueSnapshot(String sessionId) {
		List<QueueItemEntity> items = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(sessionId);
		PlayoutSessionEntity session = playoutSessionRepository.findById(sessionId).orElseThrow();
		return new QueueSnapshotResponse(sessionId, session.getStationId(), items.stream().map(this::toQueueItem).toList(), session.getCorrelationId());
	}

	private long countReadyItems(String sessionId) {
		return queueItemRepository.countBySessionIdAndStatus(sessionId, QueueItemStatus.READY);
	}

	private QueueItemEntity getCurrentQueueItem(PlayoutSessionEntity session) {
		if (session.getCurrentQueueItemId() == null) {
			return null;
		}
		return queueItemRepository.findById(session.getCurrentQueueItemId()).orElse(null);
	}

	private void requestGenerateMusic(QueueItemEntity item) {
		eventPublisher.publishEvent(new GenerateMusicRequested(item.getId(), item.getCorrelationId()));
	}

	private SettingsDocument.PlayoutSettings playoutSettings() {
		return settingsStore.load().playout();
	}

	private int totalReadyDuration(List<QueueItemEntity> items) {
		return items.stream()
				.filter(item -> item.getStatus() == QueueItemStatus.READY)
				.mapToInt(QueueItemEntity::getDurationMs)
				.sum();
	}

	private QueueItemResponse toQueueItem(QueueItemEntity item) {
		return new QueueItemResponse(
				item.getId(),
				item.getProgramBlockId(),
				item.getProgramSlotId(),
				item.getSlotRole().name(),
				item.getSegmentType().name(),
				item.getTitle(),
				item.getPlaybackMode(),
				item.getAssetUrl(),
				item.getSpeechDirectiveId(),
				item.getDurationMs(),
				item.getStatus(),
				item.getCorrelationId(),
				item.isAssetBanned(),
				normalizeContentOrigin(item.getContentOrigin()),
				item.getCreatedAt(),
				item.getReplayOfPlayHistoryId(),
				item.getLetterId());
	}

	private String normalizeContentOrigin(String contentOrigin) {
		return contentOrigin == null || contentOrigin.isBlank() ? "LIVE_GEN" : contentOrigin;
	}

	private PlayoutSessionEntity getLatestSessionOrThrow() {
		return playoutSessionRepository.findFirstByOrderByStartedAtDesc()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "再生セッションがまだ存在しません。", Map.of()));
	}

	private String nextId(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	public record PreGenerationResult(
			int materializedProgramCount,
			int materializedSegmentCount,
			int queuedMusicCount) {
	}
}
