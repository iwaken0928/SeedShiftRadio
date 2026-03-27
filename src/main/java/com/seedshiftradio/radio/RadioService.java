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

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.domain.PlayoutState;
import com.seedshiftradio.domain.ProgramBlockSlotStatus;
import com.seedshiftradio.domain.ProgramBlockStatus;
import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.programming.ProgrammingService;
import com.seedshiftradio.programming.ProgrammingService.ResolvedProgramPlan;
import com.seedshiftradio.programming.ProgrammingService.ResolvedSlot;
import com.seedshiftradio.station.StationRepository;
import com.seedshiftradio.stream.StreamEventService;

@Service
public class RadioService {

	private static final int TARGET_READY_COUNT = 3;
	private static final int MINIMUM_READY_COUNT = 2;
	private static final int MIN_READY_DURATION_MS = 90_000;
	private static final int MIN_REMAINING_SLOT_COUNT = 2;

	private final StationRepository stationRepository;
	private final ProgrammingService programmingService;
	private final PlayoutSessionRepository playoutSessionRepository;
	private final ProgramBlockRepository programBlockRepository;
	private final ProgramBlockSlotRepository programBlockSlotRepository;
	private final QueueItemRepository queueItemRepository;
	private final StreamEventService streamEventService;
	private final ClientCapabilitiesService clientCapabilitiesService;
	private final SpeechDirectiveAssembler speechDirectiveAssembler;
	private final ApplicationEventPublisher eventPublisher;
	private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

	public RadioService(
			StationRepository stationRepository,
			ProgrammingService programmingService,
			PlayoutSessionRepository playoutSessionRepository,
			ProgramBlockRepository programBlockRepository,
			ProgramBlockSlotRepository programBlockSlotRepository,
			QueueItemRepository queueItemRepository,
			StreamEventService streamEventService,
			ClientCapabilitiesService clientCapabilitiesService,
			SpeechDirectiveAssembler speechDirectiveAssembler,
			ApplicationEventPublisher eventPublisher) {
		this.stationRepository = stationRepository;
		this.programmingService = programmingService;
		this.playoutSessionRepository = playoutSessionRepository;
		this.programBlockRepository = programBlockRepository;
		this.programBlockSlotRepository = programBlockSlotRepository;
		this.queueItemRepository = queueItemRepository;
		this.streamEventService = streamEventService;
		this.clientCapabilitiesService = clientCapabilitiesService;
		this.speechDirectiveAssembler = speechDirectiveAssembler;
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

		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setId(nextId("playout"));
		session.setStationId(request.stationId());
		session.setState(PlayoutState.PREPARING);
		session.setBufferReadyCount(0);
		session.setCorrelationId(correlationId);
		session = playoutSessionRepository.save(session);
		emitSessionEvents(session.getId());
		requestQueueWarmup(session.getId());
		return new TuneResponse(session.getId(), session.getStationId(), session.getState(), true, correlationId);
	}

	@Transactional
	public RadioStatusResponse play() {
		PlayoutSessionEntity session = getLatestSessionOrThrow();
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
		PlayoutSessionEntity session = getLatestSessionOrThrow();
		stopPlayback(session);
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
		ProgramBlockEntity block = programBlockRepository.findById(session.getCurrentProgramBlockId())
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
		return speechDirectiveAssembler.assemble(session, item, clientId);
	}

	@Transactional
	public void recordPlaybackEvent(PlaybackEventRequest request) {
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
				requestQueueRefill(session.getId());
			}
			case SEGMENT_ERROR -> {
				item.setStatus(QueueItemStatus.FAILED);
				item.setAssetBanned(true);
				if (item.getId().equals(session.getCurrentQueueItemId())) {
					session.setCurrentQueueItemId(null);
				}
				session.setState(PlayoutState.DEGRADED);
				session.setDegradedReason("SEGMENT_ERROR");
				queueItemRepository.save(item);
				requestQueueRefill(session.getId());
			}
			case PLAYBACK_STOPPED -> stopPlayback(session);
			default -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "未対応の playback event です。", Map.of("eventType", request.eventType()));
		}
		refreshSessionState(session);
		emitSessionEvents(session.getId());
	}

	@Transactional(readOnly = true)
	public HealthResponse health() {
		Optional<PlayoutSessionEntity> latest = playoutSessionRepository.findFirstByOrderByStartedAtDesc();
		String latestEventId = latest.map(PlayoutSessionEntity::getCorrelationId).orElse(null);
		return new HealthResponse(
				"UP",
				Instant.now(),
				stationRepository.count(),
				playoutSessionRepository.count(),
				queueItemRepository.count(),
				latestEventId,
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

	@Transactional
	public void warmupQueue(String sessionId) {
		withSessionLock(sessionId, () -> maintainQueue(sessionId, true));
	}

	@Transactional
	public void refillQueue(String sessionId) {
		withSessionLock(sessionId, () -> maintainQueue(sessionId, false));
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
		PlayoutSessionEntity session = playoutSessionRepository.findById(sessionId).orElse(null);
		if (session == null || shouldSkipQueueMaintenance(session)) {
			return;
		}
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
		ensureBuffer(session);
		refreshSessionState(session);
		emitSessionEvents(sessionId);
	}

	private void materializeInitialQueue(PlayoutSessionEntity session, ProgramBlockEntity block, ResolvedProgramPlan plan) {
		List<ProgramBlockSlotEntity> blockSlots = programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(block.getId());
		int sequenceStart = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId()).size() + 1;
		List<QueueItemEntity> queueItems = new ArrayList<>();
		for (int i = 0; i < Math.min(TARGET_READY_COUNT, blockSlots.size()); i++) {
			ProgramBlockSlotEntity blockSlot = blockSlots.get(i);
			queueItems.add(createQueueItem(session, block, blockSlot, sequenceStart++));
			blockSlot.setStatus(ProgramBlockSlotStatus.QUEUED);
		}
		queueItemRepository.saveAll(queueItems);
		programBlockSlotRepository.saveAll(blockSlots);
		if (plan.fallbackApplied()) {
			session.setState(PlayoutState.DEGRADED);
			session.setDegradedReason("LEGACY_RATIO");
		}
	}

	private QueueItemEntity createQueueItem(PlayoutSessionEntity session, ProgramBlockEntity block, ProgramBlockSlotEntity blockSlot, int sequenceNo) {
		QueueItemEntity entity = new QueueItemEntity();
		entity.setId(nextId("queue"));
		entity.setSessionId(session.getId());
		entity.setSequenceNo(sequenceNo);
		entity.setSegmentType(blockSlot.getResolvedSegmentType());
		entity.setStatus(QueueItemStatus.READY);
		entity.setProgramBlockId(block.getId());
		entity.setProgramSlotId(blockSlot.getId());
		entity.setSlotRole(blockSlot.getRole());
		entity.setTitle(buildTitle(blockSlot));
		entity.setPlaybackMode(PlaybackMode.SERVER_AUDIO);
		entity.setAssetUrl("/api/assets/audio/" + entity.getId() + ".wav");
		entity.setSpeechDirectiveId("sd-" + entity.getId());
		entity.setDurationMs(blockSlot.getTargetDurationMs());
		entity.setCorrelationId(session.getCorrelationId());
		return entity;
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

	private void ensureBuffer(PlayoutSessionEntity session) {
		List<QueueItemEntity> items = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId());
		long readyCount = items.stream().filter(item -> item.getStatus() == QueueItemStatus.READY).count();
		int readyDuration = items.stream()
				.filter(item -> item.getStatus() == QueueItemStatus.READY)
				.mapToInt(QueueItemEntity::getDurationMs)
				.sum();
		ProgramBlockEntity currentBlock = getCurrentProgramBlock(session);
		ProgramBlockEntity latestBlock = planNextProgramBlockIfNeeded(session, currentBlock);
		if (readyCount >= MINIMUM_READY_COUNT && readyDuration >= MIN_READY_DURATION_MS) {
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
				additions.add(createQueueItem(session, refillBlock, blockSlot, nextSequence++));
				blockSlot.setStatus(ProgramBlockSlotStatus.QUEUED);
				changedSlots.add(blockSlot);
				readyCount++;
				readyDuration += blockSlot.getTargetDurationMs();
			}
			if (readyCount >= TARGET_READY_COUNT && readyDuration >= MIN_READY_DURATION_MS) {
				break;
			}
		}
		if (additions.isEmpty()) {
			additions.add(createFallbackQueueItem(session, nextSequence));
			session.setState(PlayoutState.DEGRADED);
			session.setDegradedReason("LEGACY_RATIO");
			streamEventService.publish("buffer.warning", Map.of("sessionId", session.getId(), "readyCount", readyCount));
		}
		queueItemRepository.saveAll(additions);
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

	private ProgramBlockEntity planNextProgramBlockIfNeeded(PlayoutSessionEntity session, ProgramBlockEntity currentBlock) {
		ProgramBlockEntity latestBlock = programBlockRepository.findTopBySessionIdOrderByStartedAtDesc(session.getId()).orElse(currentBlock);
		if (currentBlock == null) {
			return latestBlock;
		}
		if (latestBlock != null && !latestBlock.getId().equals(currentBlock.getId())) {
			return latestBlock;
		}
		long remainingSlotCount = programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(currentBlock.getId()).stream()
				.filter(slot -> slot.getStatus() != ProgramBlockSlotStatus.DONE && slot.getStatus() != ProgramBlockSlotStatus.SKIPPED)
				.count();
		if (remainingSlotCount >= MIN_REMAINING_SLOT_COUNT) {
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
		entity.setAssetUrl("/api/assets/audio/" + entity.getId() + ".wav");
		entity.setSpeechDirectiveId("sd-" + entity.getId());
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

	private boolean shouldSkipQueueMaintenance(PlayoutSessionEntity session) {
		if (session.getState() == PlayoutState.STOPPED || session.getState() == PlayoutState.ERROR) {
			return true;
		}
		return playoutSessionRepository.findFirstByOrderByStartedAtDesc()
				.map(latest -> !latest.getId().equals(session.getId()))
				.orElse(false);
	}

	private void withSessionLock(String sessionId, Runnable action) {
		ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, ignored -> new ReentrantLock());
		lock.lock();
		try {
			action.run();
		} finally {
			lock.unlock();
			if (!lock.hasQueuedThreads()) {
				sessionLocks.remove(sessionId, lock);
			}
		}
	}

	private void refreshSessionState(PlayoutSessionEntity session) {
		long readyCount = queueItemRepository.countBySessionIdAndStatus(session.getId(), QueueItemStatus.READY);
		session.setBufferReadyCount((int) readyCount);
		playoutSessionRepository.save(session);
	}

	private void emitSessionEvents(String sessionId) {
		PlayoutSessionEntity session = playoutSessionRepository.findById(sessionId).orElseThrow();
		streamEventService.publish("radio.status.changed", getStatus());
		streamEventService.publish("queue.updated", toQueueSnapshot(sessionId));
		if (session.getCurrentProgramBlockId() != null) {
			streamEventService.publish("program.changed", getProgram());
		}
	}

	private QueueSnapshotResponse toQueueSnapshot(String sessionId) {
		List<QueueItemEntity> items = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(sessionId);
		PlayoutSessionEntity session = playoutSessionRepository.findById(sessionId).orElseThrow();
		return new QueueSnapshotResponse(sessionId, session.getStationId(), items.stream().map(this::toQueueItem).toList(), session.getCorrelationId());
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
				item.isAssetBanned());
	}

	private PlayoutSessionEntity getLatestSessionOrThrow() {
		return playoutSessionRepository.findFirstByOrderByStartedAtDesc()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "再生セッションがまだ存在しません。", Map.of()));
	}

	private String nextId(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
