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

	private final StationRepository stationRepository;
	private final ProgrammingService programmingService;
	private final PlayoutSessionRepository playoutSessionRepository;
	private final ProgramBlockRepository programBlockRepository;
	private final ProgramBlockSlotRepository programBlockSlotRepository;
	private final QueueItemRepository queueItemRepository;
	private final StreamEventService streamEventService;
	private final Map<String, ClientCapabilitiesRequest> clientCapabilities = new ConcurrentHashMap<>();

	public RadioService(
			StationRepository stationRepository,
			ProgrammingService programmingService,
			PlayoutSessionRepository playoutSessionRepository,
			ProgramBlockRepository programBlockRepository,
			ProgramBlockSlotRepository programBlockSlotRepository,
			QueueItemRepository queueItemRepository,
			StreamEventService streamEventService) {
		this.stationRepository = stationRepository;
		this.programmingService = programmingService;
		this.playoutSessionRepository = playoutSessionRepository;
		this.programBlockRepository = programBlockRepository;
		this.programBlockSlotRepository = programBlockSlotRepository;
		this.queueItemRepository = queueItemRepository;
		this.streamEventService = streamEventService;
	}

	@Transactional
	public ClientCapabilitiesResponse registerCapabilities(ClientCapabilitiesRequest request) {
		clientCapabilities.put(request.clientId(), request);
		return new ClientCapabilitiesResponse(
				request.clientId(),
				request.clientType(),
				request.supportsClientSideTts(),
				request.supportedVoiceEngines(),
				request.preferredPlaybackMode(),
				request.localVoiceProfiles(),
				Instant.now(),
				null);
	}

	@Transactional
	public TuneResponse tune(TuneRequest request, String correlationId) {
		var station = stationRepository.findById(request.stationId())
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "指定された局が見つかりません。", Map.of("stationId", request.stationId())));
		if (!station.isActive()) {
			throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "指定された局は無効化されています。", Map.of("stationId", request.stationId()));
		}

		ResolvedProgramPlan plan = programmingService.resolveCurrentPlan(request.stationId(), OffsetDateTime.now());
		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setId(nextId("playout"));
		session.setStationId(request.stationId());
		session.setState(PlayoutState.PREPARING);
		session.setBufferReadyCount(0);
		session.setCorrelationId(correlationId);
		session = playoutSessionRepository.save(session);

		ProgramBlockEntity block = createProgramBlock(session, plan);
		session.setCurrentProgramBlockId(block.getId());
		playoutSessionRepository.save(session);

		materializeInitialQueue(session, block, plan);
		refreshSessionState(session);
		emitSessionEvents(session.getId());
		return new TuneResponse(session.getId(), session.getStationId(), session.getState(), true, correlationId);
	}

	@Transactional
	public RadioStatusResponse play() {
		PlayoutSessionEntity session = getLatestSessionOrThrow();
		QueueItemEntity item = queueItemRepository.findTopBySessionIdAndStatusOrderBySequenceNoAsc(session.getId(), QueueItemStatus.READY)
				.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "QUEUE_NOT_READY", "再生可能なセグメントがまだありません。", Map.of("sessionId", session.getId())));
		item.setStatus(QueueItemStatus.PLAYING);
		queueItemRepository.save(item);
		session.setCurrentQueueItemId(item.getId());
		session.setState(session.getDegradedReason() == null ? PlayoutState.PLAYING : PlayoutState.DEGRADED);
		refreshSessionState(session);
		emitSessionEvents(session.getId());
		return getStatus();
	}

	@Transactional
	public RadioStatusResponse stop() {
		PlayoutSessionEntity session = getLatestSessionOrThrow();
		session.setState(PlayoutState.STOPPED);
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
	public SpeechDirectiveResponse getNextSpeechDirective() {
		QueueItemResponse item = getNextSegment();
		return new SpeechDirectiveResponse(
				item.speechDirectiveId() == null ? "sd-" + item.id() : item.speechDirectiveId(),
				item.title(),
				item.title(),
				List.of(),
				"calm",
				"medium",
				List.of(),
				"persona-night-main",
				"voicevox:4",
				getLatestSessionOrThrow().getCorrelationId());
	}

	@Transactional
	public void recordPlaybackEvent(PlaybackEventRequest request) {
		PlayoutSessionEntity session = playoutSessionRepository.findById(request.sessionId())
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "再生セッションが見つかりません。", Map.of("sessionId", request.sessionId())));
		QueueItemEntity item = queueItemRepository.findById(request.itemId())
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "QueueItem が見つかりません。", Map.of("itemId", request.itemId())));
		switch (request.eventType()) {
			case SEGMENT_STARTED -> {
				item.setStatus(QueueItemStatus.PLAYING);
				session.setCurrentQueueItemId(item.getId());
				session.setState(session.getDegradedReason() == null ? PlayoutState.PLAYING : PlayoutState.DEGRADED);
			}
			case SEGMENT_ENDED -> {
				item.setStatus(QueueItemStatus.DONE);
				markSlotDone(item.getProgramSlotId());
				ensureBuffer(session);
			}
			case SEGMENT_ERROR -> {
				item.setStatus(QueueItemStatus.FAILED);
				item.setAssetBanned(true);
				session.setState(PlayoutState.DEGRADED);
				session.setDegradedReason("SEGMENT_ERROR");
				ensureBuffer(session);
			}
			case PLAYBACK_STOPPED -> session.setState(PlayoutState.STOPPED);
			default -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "未対応の playback event です。", Map.of("eventType", request.eventType()));
		}
		queueItemRepository.save(item);
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
				latest.map(PlayoutSessionEntity::getId).orElse(null));
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

	private ProgramBlockEntity createProgramBlock(PlayoutSessionEntity session, ResolvedProgramPlan plan) {
		ProgramBlockEntity block = new ProgramBlockEntity();
		block.setId(nextId("program"));
		block.setStationId(session.getStationId());
		block.setSessionId(session.getId());
		block.setProgramTemplateId(plan.templateId());
		block.setProgramTemplateVersion(plan.templateVersion());
		block.setTitle(plan.title());
		block.setStatus(ProgramBlockStatus.ACTIVE);
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
		if (readyCount >= MINIMUM_READY_COUNT && readyDuration >= MIN_READY_DURATION_MS) {
			return;
		}
		List<ProgramBlockSlotEntity> blockSlots = session.getCurrentProgramBlockId() == null ? List.of()
				: programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(session.getCurrentProgramBlockId());
		int nextSequence = items.size() + 1;
		List<QueueItemEntity> additions = new ArrayList<>();
		for (ProgramBlockSlotEntity blockSlot : blockSlots) {
			if (blockSlot.getStatus() == ProgramBlockSlotStatus.PLANNED) {
				additions.add(createQueueItem(session,
						programBlockRepository.findById(session.getCurrentProgramBlockId()).orElseThrow(),
						blockSlot,
						nextSequence++));
				blockSlot.setStatus(ProgramBlockSlotStatus.QUEUED);
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
		programBlockSlotRepository.saveAll(blockSlots);
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
