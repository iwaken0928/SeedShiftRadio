package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.PlayoutState;
import com.seedshiftradio.domain.ProgramBlockSlotStatus;
import com.seedshiftradio.domain.ProgramBlockStatus;
import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.programming.ProgrammingService;
import com.seedshiftradio.station.StationRepository;
import com.seedshiftradio.stream.StreamEventService;

@ExtendWith(MockitoExtension.class)
class RadioServiceStateMachineTests {

	@Mock
	StationRepository stationRepository;

	@Mock
	ProgrammingService programmingService;

	@Mock
	PlayoutSessionRepository playoutSessionRepository;

	@Mock
	ProgramBlockRepository programBlockRepository;

	@Mock
	ProgramBlockSlotRepository programBlockSlotRepository;

	@Mock
	QueueItemRepository queueItemRepository;

	@Mock
	StreamEventService streamEventService;

	RadioService radioService;

	@BeforeEach
	void setUp() {
		radioService = new RadioService(
				stationRepository,
				programmingService,
				playoutSessionRepository,
				programBlockRepository,
				programBlockSlotRepository,
				queueItemRepository,
				streamEventService);
	}

	@Test
	void recordPlaybackEventRejectsQueueItemFromDifferentSession() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-002");
		QueueItemEntity item = queueItem("queue-001", "playout-999", QueueItemStatus.PLAYING);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		when(queueItemRepository.findById("queue-001")).thenReturn(Optional.of(item));

		ApiException exception = assertThrows(
				ApiException.class,
				() -> radioService.recordPlaybackEvent(new PlaybackEventRequest(
						"web-client",
						"playout-001",
						"queue-001",
						PlaybackEventType.SEGMENT_STARTED,
						Instant.now())));

		assertEquals("CONFLICT", exception.getCode());
	}

	@Test
	void recordPlaybackEventRejectsInvalidStateTransition() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-001");
		QueueItemEntity item = queueItem("queue-001", "playout-001", QueueItemStatus.READY);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		when(queueItemRepository.findById("queue-001")).thenReturn(Optional.of(item));

		ApiException exception = assertThrows(
				ApiException.class,
				() -> radioService.recordPlaybackEvent(new PlaybackEventRequest(
						"web-client",
						"playout-001",
						"queue-001",
						PlaybackEventType.SEGMENT_ENDED,
						Instant.now())));

		assertEquals("CONFLICT", exception.getCode());
	}

	@Test
	void stopReturnsCurrentPlayingItemToReady() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-001");
		QueueItemEntity item = queueItem("queue-001", "playout-001", QueueItemStatus.PLAYING);
		when(playoutSessionRepository.save(any(PlayoutSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(queueItemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(playoutSessionRepository.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.of(session));
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		when(queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001")).thenReturn(List.of(item));
		when(queueItemRepository.countBySessionIdAndStatus("playout-001", QueueItemStatus.READY)).thenReturn(1L);

		RadioStatusResponse response = radioService.stop();

		assertEquals(QueueItemStatus.READY, item.getStatus());
		assertNull(session.getCurrentQueueItemId());
		assertEquals(PlayoutState.STOPPED, response.state());
	}

	@Test
	void recordPlaybackEventPlansNextProgramBlockBeforeFallingBack() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-002");
		session.setCurrentProgramBlockId("block-current");

		ProgramBlockEntity currentBlock = programBlock("block-current", "playout-001", ProgramBlockStatus.ACTIVE);
		List<ProgramBlockSlotEntity> currentSlots = new ArrayList<>(List.of(
				blockSlot("slot-1", "block-current", ProgramBlockSlotStatus.DONE, SlotRole.OPENING, 30_000),
				blockSlot("slot-2", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.TOPIC, 60_000),
				blockSlot("slot-3", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.ENDING, 30_000)));

		QueueItemEntity done = queueItem("queue-001", "playout-001", QueueItemStatus.DONE);
		done.setProgramBlockId("block-current");
		done.setProgramSlotId("slot-1");
		QueueItemEntity current = queueItem("queue-002", "playout-001", QueueItemStatus.PLAYING);
		current.setProgramBlockId("block-current");
		current.setProgramSlotId("slot-2");
		QueueItemEntity ready = queueItem("queue-003", "playout-001", QueueItemStatus.READY);
		ready.setProgramBlockId("block-current");
		ready.setProgramSlotId("slot-3");
		List<QueueItemEntity> queueItems = new ArrayList<>(List.of(done, current, ready));

		wireRepositoryState(session, List.of(currentBlock), Map.of("block-current", currentSlots), queueItems);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		when(programmingService.resolveCurrentPlan(anyString(), any(OffsetDateTime.class))).thenReturn(new ProgrammingService.ResolvedProgramPlan(
				"tmpl-next",
				4,
				"次の番組",
				120_000,
				List.of(
						new ProgrammingService.ResolvedSlot("next-slot-1", com.seedshiftradio.domain.SlotRole.OPENING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("next-slot-2", com.seedshiftradio.domain.SlotRole.TOPIC, com.seedshiftradio.domain.ConstraintMode.SOFT, 60_000, SegmentType.TALK)),
				false,
				List.of()));

		radioService.recordPlaybackEvent(new PlaybackEventRequest(
				"web-client",
				"playout-001",
				"queue-002",
				PlaybackEventType.SEGMENT_ENDED,
				Instant.now()));

		List<QueueItemEntity> updatedItems = queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001");
		String nextBlockId = updatedItems.stream()
				.map(QueueItemEntity::getProgramBlockId)
				.filter(programBlockId -> programBlockId != null && !"block-current".equals(programBlockId))
				.findFirst()
				.orElseThrow();

		assertNotEquals("block-current", nextBlockId);
		assertEquals("block-current", session.getCurrentProgramBlockId());
		assertEquals(QueueItemStatus.DONE, current.getStatus());
		assertEquals(ProgramBlockStatus.PLANNED, programBlockRepository.findById(nextBlockId).orElseThrow().getStatus());
	}

	@Test
	void recordPlaybackEventAdvancesToPlannedNextProgramBlockAfterCurrentCompletes() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-002");
		session.setCurrentProgramBlockId("block-current");

		ProgramBlockEntity currentBlock = programBlock("block-current", "playout-001", ProgramBlockStatus.ACTIVE);
		ProgramBlockEntity nextBlock = programBlock("block-next", "playout-001", ProgramBlockStatus.PLANNED);
		nextBlock.setStartedAt(Instant.parse("2026-03-20T10:00:00Z"));

		List<ProgramBlockSlotEntity> currentSlots = new ArrayList<>(List.of(
				blockSlot("slot-1", "block-current", ProgramBlockSlotStatus.DONE, SlotRole.OPENING, 30_000),
				blockSlot("slot-2", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.TOPIC, 60_000)));
		List<ProgramBlockSlotEntity> nextSlots = new ArrayList<>(List.of(
				blockSlot("next-slot-1", "block-next", ProgramBlockSlotStatus.QUEUED, SlotRole.OPENING, 30_000),
				blockSlot("next-slot-2", "block-next", ProgramBlockSlotStatus.PLANNED, SlotRole.TOPIC, 60_000)));

		QueueItemEntity done = queueItem("queue-001", "playout-001", QueueItemStatus.DONE);
		done.setProgramBlockId("block-current");
		done.setProgramSlotId("slot-1");
		QueueItemEntity current = queueItem("queue-002", "playout-001", QueueItemStatus.PLAYING);
		current.setProgramBlockId("block-current");
		current.setProgramSlotId("slot-2");
		QueueItemEntity nextReady = queueItem("queue-003", "playout-001", QueueItemStatus.READY);
		nextReady.setProgramBlockId("block-next");
		nextReady.setProgramSlotId("next-slot-1");
		List<QueueItemEntity> queueItems = new ArrayList<>(List.of(done, current, nextReady));

		wireRepositoryState(
				session,
				List.of(currentBlock, nextBlock),
				Map.of("block-current", currentSlots, "block-next", nextSlots),
				queueItems);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));

		radioService.recordPlaybackEvent(new PlaybackEventRequest(
				"web-client",
				"playout-001",
				"queue-002",
				PlaybackEventType.SEGMENT_ENDED,
				Instant.now()));

		assertEquals(ProgramBlockStatus.DONE, currentBlock.getStatus());
		assertEquals(ProgramBlockStatus.ACTIVE, nextBlock.getStatus());
		assertEquals("block-next", session.getCurrentProgramBlockId());
	}

	private PlayoutSessionEntity session(String id, PlayoutState state, String currentQueueItemId) {
		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setId(id);
		session.setStationId("station-night");
		session.setState(state);
		session.setCurrentQueueItemId(currentQueueItemId);
		session.setBufferReadyCount(1);
		session.setCorrelationId("corr-001");
		return session;
	}

	private QueueItemEntity queueItem(String id, String sessionId, QueueItemStatus status) {
		QueueItemEntity item = new QueueItemEntity();
		item.setId(id);
		item.setSessionId(sessionId);
		item.setSequenceNo(1);
		item.setSegmentType(SegmentType.TALK);
		item.setStatus(status);
		item.setSlotRole(SlotRole.TOPIC);
		item.setTitle("トーク");
		item.setCorrelationId("corr-001");
		item.setDurationMs(60_000);
		item.setAssetUrl("/api/assets/audio/" + id + ".wav");
		return item;
	}

	private ProgramBlockEntity programBlock(String id, String sessionId, ProgramBlockStatus status) {
		ProgramBlockEntity block = new ProgramBlockEntity();
		block.setId(id);
		block.setSessionId(sessionId);
		block.setStationId("station-night");
		block.setStatus(status);
		block.setTitle("番組");
		block.setPlannedDurationMs(120_000);
		block.setStartedAt(Instant.parse("2026-03-20T09:00:00Z"));
		return block;
	}

	private ProgramBlockSlotEntity blockSlot(String id, String blockId, ProgramBlockSlotStatus status, SlotRole role, int durationMs) {
		ProgramBlockSlotEntity slot = new ProgramBlockSlotEntity();
		slot.setId(id);
		slot.setProgramBlockId(blockId);
		slot.setStatus(status);
		slot.setRole(role);
		slot.setConstraintMode(com.seedshiftradio.domain.ConstraintMode.SOFT);
		slot.setResolvedSegmentType(SegmentType.TALK);
		slot.setTargetDurationMs(durationMs);
		slot.setSequenceNo(1);
		return slot;
	}

	private void wireRepositoryState(
			PlayoutSessionEntity session,
			List<ProgramBlockEntity> initialBlocks,
			Map<String, List<ProgramBlockSlotEntity>> initialSlotsByBlockId,
			List<QueueItemEntity> queueItems) {
		Map<String, ProgramBlockEntity> blocksById = new HashMap<>();
		AtomicLong blockOrder = new AtomicLong(initialBlocks.size());
		for (ProgramBlockEntity block : initialBlocks) {
			blocksById.put(block.getId(), block);
		}

		Map<String, List<ProgramBlockSlotEntity>> slotsByBlockId = new HashMap<>();
		for (Map.Entry<String, List<ProgramBlockSlotEntity>> entry : initialSlotsByBlockId.entrySet()) {
			slotsByBlockId.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}

		List<QueueItemEntity> sessionQueueItems = new ArrayList<>(queueItems);

		when(playoutSessionRepository.save(any(PlayoutSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(playoutSessionRepository.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.of(session));
		when(programBlockRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(blocksById.get(invocation.getArgument(0))));
		when(programBlockRepository.findTopBySessionIdOrderByStartedAtDesc(session.getId())).thenAnswer(invocation -> blocksById.values().stream()
				.filter(block -> session.getId().equals(block.getSessionId()))
				.max(Comparator.comparing(ProgramBlockEntity::getStartedAt)));
		when(programBlockRepository.save(any(ProgramBlockEntity.class))).thenAnswer(invocation -> {
			ProgramBlockEntity block = invocation.getArgument(0);
			if (block.getStartedAt() == null) {
				block.setStartedAt(Instant.ofEpochMilli(blockOrder.incrementAndGet()));
			}
			blocksById.put(block.getId(), block);
			return block;
		});

		when(programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> slotsByBlockId
				.getOrDefault(invocation.getArgument(0), List.of()));
		when(programBlockSlotRepository.findById(anyString())).thenAnswer(invocation -> slotsByBlockId.values().stream()
				.flatMap(List::stream)
				.filter(slot -> slot.getId().equals(invocation.getArgument(0)))
				.findFirst());
		when(programBlockSlotRepository.save(any(ProgramBlockSlotEntity.class))).thenAnswer(invocation -> {
			ProgramBlockSlotEntity slot = invocation.getArgument(0);
			replaceSlot(slotsByBlockId, slot);
			return slot;
		});
		when(programBlockSlotRepository.saveAll(any())).thenAnswer(invocation -> {
			List<ProgramBlockSlotEntity> slots = invocation.getArgument(0);
			for (ProgramBlockSlotEntity slot : slots) {
				replaceSlot(slotsByBlockId, slot);
			}
			return slots;
		});

		when(queueItemRepository.findById(anyString())).thenAnswer(invocation -> sessionQueueItems.stream()
				.filter(item -> item.getId().equals(invocation.getArgument(0)))
				.findFirst());
		when(queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId())).thenAnswer(invocation -> sessionQueueItems.stream()
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.toList());
		when(queueItemRepository.save(any(QueueItemEntity.class))).thenAnswer(invocation -> {
			QueueItemEntity item = invocation.getArgument(0);
			replaceQueueItem(sessionQueueItems, item);
			return item;
		});
		when(queueItemRepository.saveAll(any())).thenAnswer(invocation -> {
			List<QueueItemEntity> items = invocation.getArgument(0);
			for (QueueItemEntity item : items) {
				replaceQueueItem(sessionQueueItems, item);
			}
			return items;
		});
		when(queueItemRepository.countBySessionIdAndStatus(session.getId(), QueueItemStatus.READY)).thenAnswer(invocation -> sessionQueueItems.stream()
				.filter(item -> item.getStatus() == QueueItemStatus.READY)
				.count());
	}

	private void replaceSlot(Map<String, List<ProgramBlockSlotEntity>> slotsByBlockId, ProgramBlockSlotEntity slot) {
		List<ProgramBlockSlotEntity> slots = slotsByBlockId.computeIfAbsent(slot.getProgramBlockId(), ignored -> new ArrayList<>());
		for (int index = 0; index < slots.size(); index++) {
			if (slots.get(index).getId().equals(slot.getId())) {
				slots.set(index, slot);
				return;
			}
		}
		slots.add(slot);
	}

	private void replaceQueueItem(List<QueueItemEntity> queueItems, QueueItemEntity item) {
		for (int index = 0; index < queueItems.size(); index++) {
			if (queueItems.get(index).getId().equals(item.getId())) {
				queueItems.set(index, item);
				return;
			}
		}
		queueItems.add(item);
	}
}
