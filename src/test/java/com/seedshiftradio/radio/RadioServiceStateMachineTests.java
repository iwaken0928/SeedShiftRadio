package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.PlayoutState;
import com.seedshiftradio.domain.ProgramBlockSlotStatus;
import com.seedshiftradio.domain.ProgramBlockStatus;
import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport;
import com.seedshiftradio.programming.ProgrammingService;
import com.seedshiftradio.programming.StationProgrammingPolicyEntity;
import com.seedshiftradio.programming.StationProgrammingPolicyRepository;
import com.seedshiftradio.settings.AssetService;
import com.seedshiftradio.settings.RadioSettingsStore;
import com.seedshiftradio.settings.SettingsDocument;
import com.seedshiftradio.station.StationRepository;
import com.seedshiftradio.station.StationEntity;
import com.seedshiftradio.stream.StreamEventService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RadioServiceStateMachineTests {

	@Mock
	StationRepository stationRepository;

	@Mock
	ProgrammingService programmingService;

	@Mock
	StationProgrammingPolicyRepository programmingPolicyRepository;

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

	@Mock
	RadioSettingsStore settingsStore;

	@Mock
	AssetService assetService;

	@Mock
	ClientCapabilitiesService clientCapabilitiesService;

	@Mock
	ScriptGenerationService scriptGenerationService;

	@Mock
	PlayHistoryService playHistoryService;

	@Mock
	LetterSegmentBinder letterSegmentBinder;

	@Mock
	BroadcastArchiveService broadcastArchiveService;

	@Mock
	ApplicationEventPublisher eventPublisher;

	RadioService radioService;

	@BeforeEach
	void setUp() {
		radioService = new RadioService(
				stationRepository,
				programmingService,
				programmingPolicyRepository,
				playoutSessionRepository,
				programBlockRepository,
				programBlockSlotRepository,
				queueItemRepository,
				streamEventService,
				settingsStore,
				assetService,
				clientCapabilitiesService,
				scriptGenerationService,
				playHistoryService,
				letterSegmentBinder,
				broadcastArchiveService,
				eventPublisher);
		when(programmingPolicyRepository.findByStationId(anyString())).thenReturn(Optional.empty());
		when(broadcastArchiveService.findReplayCandidate(anyString(), any(SegmentType.class), anyString(), anyInt())).thenReturn(Optional.empty());
		when(settingsStore.load()).thenReturn(settingsDocument(new SettingsDocument.PlayoutSettings(3, 2, 90_000, 480_000, 2, 4, 3, 2, true)));
		when(scriptGenerationService.resolveDirective(any(PlayoutSessionEntity.class), any(QueueItemEntity.class), nullable(String.class))).thenAnswer(invocation -> {
			PlayoutSessionEntity session = invocation.getArgument(0);
			QueueItemEntity item = invocation.getArgument(1);
			String speechDirectiveId = item.getSpeechDirectiveId() == null ? "sd-" + item.getId() : item.getSpeechDirectiveId();
			String text = item.getTitle() == null ? "字幕" : item.getTitle();
			return new SpeechDirectiveResponse(
					speechDirectiveId,
					text,
					text,
					List.of(),
					"calm",
					"medium",
					List.of(),
					"persona-night-main",
					"voice-night-main",
					session.getCorrelationId());
		});
		doAnswer(invocation -> {
			Object event = invocation.getArgument(0);
			if (event instanceof QueueWarmupRequested warmupRequested) {
				radioService.warmupQueue(warmupRequested.sessionId());
			} else if (event instanceof QueueRefillRequested refillRequested) {
				radioService.refillQueue(refillRequested.sessionId());
			}
			return null;
		}).when(eventPublisher).publishEvent(any(Object.class));
	}

	@Test
	void getProgramReturnsNotReadyWhileWarmupHasNotCreatedProgramBlock() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PREPARING, null);
		when(playoutSessionRepository.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.of(session));

		ApiException exception = assertThrows(ApiException.class, () -> radioService.getProgram());

		assertEquals("PROGRAM_NOT_READY", exception.getCode());
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
	void recordPlaybackEventAcceptsDuplicateSegmentEndedAsIdempotent() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PREPARING, null);
		QueueItemEntity item = queueItem("queue-001", "playout-001", QueueItemStatus.DONE);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		when(queueItemRepository.findById("queue-001")).thenReturn(Optional.of(item));

		radioService.recordPlaybackEvent(new PlaybackEventRequest(
				"web-client",
				"playout-001",
				"queue-001",
				PlaybackEventType.SEGMENT_ENDED,
				Instant.now()));

		verify(playHistoryService, never()).record(any(), any(), any());
		verify(queueItemRepository, never()).save(item);
	}

	@Test
	void recordPlaybackEventAcceptsDuplicateSegmentErrorAsIdempotent() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.DEGRADED, null);
		QueueItemEntity item = queueItem("queue-001", "playout-001", QueueItemStatus.FAILED);
		item.setAssetBanned(true);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		when(queueItemRepository.findById("queue-001")).thenReturn(Optional.of(item));

		radioService.recordPlaybackEvent(new PlaybackEventRequest(
				"web-client",
				"playout-001",
				"queue-001",
				PlaybackEventType.SEGMENT_ERROR,
				Instant.now()));

		verify(playHistoryService, never()).record(any(), any(), any());
		verify(queueItemRepository, never()).save(item);
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
		verify(streamEventService).publish(
				eq("subtitle.updated"),
				argThat(payload -> payload instanceof SubtitlePayload subtitle
						&& "playout-001".equals(subtitle.sessionId())
						&& subtitle.itemId() == null
						&& subtitle.text().isEmpty()));
	}

	@Test
	void playPublishesSubtitleForCurrentItem() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PREPARING, null);
		QueueItemEntity item = queueItem("queue-001", "playout-001", QueueItemStatus.READY);
		wireRepositoryState(session, List.of(), Map.of(), new ArrayList<>(List.of(item)));
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		when(scriptGenerationService.resolveDirective(session, item, null)).thenReturn(new SpeechDirectiveResponse(
				"sd-queue-001",
				"字幕テキストです。",
				"字幕テキストです。",
				List.of(),
				"calm",
				"medium",
				List.of(),
				"persona-night-main",
				"voice-night-main",
				session.getCorrelationId()));
		doAnswer(invocation -> null).when(eventPublisher).publishEvent(any(Object.class));

		radioService.play();

		verify(streamEventService).publish(
				eq("subtitle.updated"),
				argThat(payload -> payload instanceof SubtitlePayload subtitle
						&& "playout-001".equals(subtitle.sessionId())
						&& "queue-001".equals(subtitle.itemId())
						&& "sd-queue-001".equals(subtitle.speechDirectiveId())
						&& "字幕テキストです。".equals(subtitle.text())));
	}

	@Test
	void playDoesNotSkipUnreadyOpeningWithinCurrentProgram() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PREPARING, null);
		session.setCurrentProgramBlockId("block-current");
		ProgramBlockEntity currentBlock = programBlock("block-current", session.getId(), ProgramBlockStatus.ACTIVE);
		ProgramBlockEntity nextBlock = programBlock("block-next", session.getId(), ProgramBlockStatus.PLANNED);
		nextBlock.setStartedAt(Instant.parse("2026-03-20T09:30:00Z"));
		QueueItemEntity opening = queueItem("queue-opening", session.getId(), QueueItemStatus.GENERATING);
		opening.setProgramBlockId(currentBlock.getId());
		opening.setSequenceNo(1);
		opening.setSlotRole(SlotRole.OPENING);
		QueueItemEntity laterReady = queueItem("queue-topic", session.getId(), QueueItemStatus.READY);
		laterReady.setProgramBlockId(currentBlock.getId());
		laterReady.setSequenceNo(2);
		QueueItemEntity nextProgramReady = queueItem("queue-next", session.getId(), QueueItemStatus.READY);
		nextProgramReady.setProgramBlockId(nextBlock.getId());
		nextProgramReady.setSequenceNo(3);
		wireRepositoryState(
				session,
				List.of(currentBlock, nextBlock),
				Map.of(currentBlock.getId(), List.of(), nextBlock.getId(), List.of()),
				new ArrayList<>(List.of(opening, laterReady, nextProgramReady)));
		when(playoutSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

		ApiException exception = assertThrows(ApiException.class, () -> radioService.play());

		assertEquals("QUEUE_NOT_READY", exception.getCode());
		assertTrue(queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId()).stream()
				.noneMatch(item -> item.getStatus() == QueueItemStatus.PLAYING));
	}

	@Test
	void playbackEventRejectsStartingNextProgramDirectly() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PREPARING, null);
		session.setCurrentProgramBlockId("block-current");
		ProgramBlockEntity currentBlock = programBlock("block-current", session.getId(), ProgramBlockStatus.ACTIVE);
		ProgramBlockEntity nextBlock = programBlock("block-next", session.getId(), ProgramBlockStatus.PLANNED);
		nextBlock.setStartedAt(Instant.parse("2026-03-20T09:30:00Z"));
		QueueItemEntity currentOpening = queueItem("queue-opening", session.getId(), QueueItemStatus.READY);
		currentOpening.setProgramBlockId(currentBlock.getId());
		currentOpening.setSequenceNo(1);
		QueueItemEntity nextProgramOpening = queueItem("queue-next", session.getId(), QueueItemStatus.READY);
		nextProgramOpening.setProgramBlockId(nextBlock.getId());
		nextProgramOpening.setSequenceNo(2);
		wireRepositoryState(
				session,
				List.of(currentBlock, nextBlock),
				Map.of(currentBlock.getId(), List.of(), nextBlock.getId(), List.of()),
				new ArrayList<>(List.of(currentOpening, nextProgramOpening)));
		when(playoutSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

		ApiException exception = assertThrows(ApiException.class, () -> radioService.recordPlaybackEvent(
				new PlaybackEventRequest(
						"web-client",
						session.getId(),
						nextProgramOpening.getId(),
						PlaybackEventType.SEGMENT_STARTED,
						Instant.now())));

		assertEquals("PROGRAM_PLAYBACK_ORDER_CONFLICT", exception.getCode());
		assertEquals(QueueItemStatus.READY, nextProgramOpening.getStatus());
	}

	@Test
	void nextSegmentDoesNotSkipUnreadyOpeningWithinCurrentProgram() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PREPARING, null);
		session.setCurrentProgramBlockId("block-current");
		QueueItemEntity opening = queueItem("queue-opening", session.getId(), QueueItemStatus.GENERATING);
		opening.setProgramBlockId("block-current");
		opening.setSequenceNo(1);
		QueueItemEntity laterReady = queueItem("queue-topic", session.getId(), QueueItemStatus.READY);
		laterReady.setProgramBlockId("block-current");
		laterReady.setSequenceNo(2);
		wireRepositoryState(session, List.of(), Map.of(), new ArrayList<>(List.of(opening, laterReady)));

		ApiException exception = assertThrows(ApiException.class, () -> radioService.getNextSegment());

		assertEquals("QUEUE_NOT_READY", exception.getCode());
	}

	@Test
	void nextSegmentReturnsFollowingReadyItemWhileCurrentItemIsPlaying() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-opening");
		session.setCurrentProgramBlockId("block-current");
		QueueItemEntity opening = queueItem("queue-opening", session.getId(), QueueItemStatus.PLAYING);
		opening.setProgramBlockId("block-current");
		opening.setSequenceNo(1);
		QueueItemEntity nextReady = queueItem("queue-topic", session.getId(), QueueItemStatus.READY);
		nextReady.setProgramBlockId("block-current");
		nextReady.setSequenceNo(2);
		QueueItemEntity nextProgramReady = queueItem("queue-next", session.getId(), QueueItemStatus.READY);
		nextProgramReady.setProgramBlockId("block-next");
		nextProgramReady.setSequenceNo(3);
		wireRepositoryState(
				session,
				List.of(),
				Map.of(),
				new ArrayList<>(List.of(opening, nextReady, nextProgramReady)));

		QueueItemResponse response = radioService.getNextSegment();

		assertEquals(nextReady.getId(), response.id());
	}

	@Test
	void tuneStopsPreviousSessionBeforeCreatingNewOne() {
		PlayoutSessionEntity previousSession = session("playout-old", PlayoutState.PLAYING, "queue-old");
		QueueItemEntity previousItem = queueItem("queue-old", "playout-old", QueueItemStatus.PLAYING);
		AtomicReference<PlayoutSessionEntity> latestSession = new AtomicReference<>(previousSession);
		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station()));
		when(playoutSessionRepository.findFirstByOrderByStartedAtDesc()).thenAnswer(invocation -> Optional.ofNullable(latestSession.get()));
		when(playoutSessionRepository.findById(anyString())).thenAnswer(invocation -> {
			String sessionId = invocation.getArgument(0);
			PlayoutSessionEntity session = latestSession.get();
			if (session != null && session.getId().equals(sessionId)) {
				return Optional.of(session);
			}
			if (previousSession.getId().equals(sessionId)) {
				return Optional.of(previousSession);
			}
			return Optional.empty();
		});
		when(playoutSessionRepository.save(any(PlayoutSessionEntity.class))).thenAnswer(invocation -> {
			PlayoutSessionEntity session = invocation.getArgument(0);
			latestSession.set(session);
			return session;
		});
		when(queueItemRepository.findById("queue-old")).thenReturn(Optional.of(previousItem));
		when(queueItemRepository.findBySessionIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> {
			String sessionId = invocation.getArgument(0);
			if ("playout-old".equals(sessionId)) {
				return List.of(previousItem);
			}
			return List.of();
		});
		when(queueItemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(queueItemRepository.countBySessionIdAndStatus(anyString(), any(QueueItemStatus.class))).thenAnswer(invocation -> {
			String sessionId = invocation.getArgument(0);
			QueueItemStatus status = invocation.getArgument(1);
			if ("playout-old".equals(sessionId) && status == QueueItemStatus.READY) {
				return 1L;
			}
			return 0L;
		});
		doAnswer(invocation -> null).when(eventPublisher).publishEvent(any(Object.class));

		radioService.tune(new TuneRequest("station-night", "tester", false), "corr-new");

		assertEquals(PlayoutState.STOPPED, previousSession.getState());
		assertNull(previousSession.getCurrentQueueItemId());
		assertEquals(QueueItemStatus.READY, previousItem.getStatus());
	}

	@Test
	void playFromStoppedSessionWarmsQueueSoRetryCanResumePlayback() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.STOPPED, null);
		session.setResumePlayback(false);
		wireRepositoryState(session, List.of(), Map.of(), new ArrayList<>());
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		when(programmingService.resolveCurrentPlan(anyString(), any(OffsetDateTime.class))).thenReturn(new ProgrammingService.ResolvedProgramPlan(
				"tmpl-night-regular",
				3,
				"深夜の作業ノート",
				120_000,
				List.of(
						new ProgrammingService.ResolvedSlot("slot-1", SlotRole.OPENING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-2", SlotRole.TOPIC, com.seedshiftradio.domain.ConstraintMode.SOFT, 60_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-3", SlotRole.ENDING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.JINGLE)),
				false,
				List.of()));

		ApiException exception = assertThrows(ApiException.class, () -> radioService.play());

		assertEquals("QUEUE_NOT_READY", exception.getCode());
		assertEquals(PlayoutState.PREPARING, session.getState());
		assertEquals(3, queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001").size());
		assertEquals(3, session.getBufferReadyCount());
		assertEquals(15_000, queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001").stream()
				.filter(item -> item.getSlotRole() == SlotRole.ENDING)
				.findFirst()
				.orElseThrow()
				.getDurationMs());

		RadioStatusResponse response = radioService.play();

		assertEquals(PlayoutState.PLAYING, response.state());
		assertEquals(1L, queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001").stream()
				.filter(item -> item.getStatus() == QueueItemStatus.PLAYING)
				.count());
	}

	@Test
	void recordPlaybackEventEndedReturnsSessionToPreparingWhenNoItemIsPlaying() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-001");
		QueueItemEntity item = queueItem("queue-001", "playout-001", QueueItemStatus.PLAYING);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		when(playoutSessionRepository.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.of(session));
		when(playoutSessionRepository.save(any(PlayoutSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(queueItemRepository.findById("queue-001")).thenReturn(Optional.of(item));
		when(queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001")).thenReturn(List.of(item));
		when(queueItemRepository.save(any(QueueItemEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(queueItemRepository.countBySessionIdAndStatus("playout-001", QueueItemStatus.READY)).thenReturn(0L);
		doAnswer(invocation -> null).when(eventPublisher).publishEvent(any(Object.class));

		radioService.recordPlaybackEvent(new PlaybackEventRequest(
				"web-client",
				"playout-001",
				"queue-001",
				PlaybackEventType.SEGMENT_ENDED,
				Instant.now()));

		assertEquals(QueueItemStatus.DONE, item.getStatus());
		assertNull(session.getCurrentQueueItemId());
		assertEquals(PlayoutState.PREPARING, session.getState());
	}

	@Test
	void synchronizeSessionAfterAsyncUpdateKeepsPreparingBeforePlaybackEvenWhenDegradedReasonExists() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PREPARING, null);
		session.setDegradedReason("LEGACY_RATIO");
		QueueItemEntity first = queueItem("queue-001", "playout-001", QueueItemStatus.READY);
		first.setAssetId("asset-queue-001");
		first.setAssetUrl("/api/assets/audio/asset-queue-001.wav");
		QueueItemEntity second = queueItem("queue-002", "playout-001", QueueItemStatus.READY);
		second.setSequenceNo(2);
		second.setAssetId("asset-queue-002");
		second.setAssetUrl("/api/assets/audio/asset-queue-002.wav");
		wireRepositoryState(session, List.of(), Map.of(), new ArrayList<>(List.of(first, second)));
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));

		radioService.synchronizeSessionAfterAsyncUpdate("playout-001");

		assertEquals(PlayoutState.PREPARING, session.getState());
		assertEquals(2, session.getBufferReadyCount());
	}

	@Test
	void refillQueuePrefetchesReadyLocalMusicAssets() {
		when(settingsStore.load()).thenReturn(settingsDocument(new SettingsDocument.PlayoutSettings(3, 2, 90_000, 480_000, 1, 4, 3, 2, true)));
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PREPARING, null);
		session.setCurrentProgramBlockId("block-current");
		ProgramBlockEntity currentBlock = programBlock("block-current", "playout-001", ProgramBlockStatus.ACTIVE);
		QueueItemEntity localMusic = queueItem("queue-001", "playout-001", QueueItemStatus.READY);
		localMusic.setSegmentType(SegmentType.MUSIC_LOCAL);
		localMusic.setSlotRole(SlotRole.MUSIC_BREAK);
		localMusic.setAssetId(null);
		localMusic.setAssetUrl(null);
		QueueItemEntity jingle = queueItem("queue-002", "playout-001", QueueItemStatus.READY);
		jingle.setSequenceNo(2);
		jingle.setAssetId("asset-queue-002");
		jingle.setAssetUrl("/api/assets/audio/asset-queue-002.wav");
		wireRepositoryState(session, List.of(currentBlock), Map.of("block-current", new ArrayList<>()), new ArrayList<>(List.of(localMusic, jingle)));
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		when(programBlockRepository.findBySessionIdOrderByStartedAtAsc("playout-001")).thenReturn(List.of(currentBlock));
		doAnswer(invocation -> {
			QueueItemEntity item = invocation.getArgument(0);
			item.setAssetId("asset-" + item.getId());
			item.setAssetUrl("/api/assets/audio/asset-" + item.getId() + ".wav");
			return null;
		}).when(assetService).ensureQueueAudioAsset(any(QueueItemEntity.class));

		radioService.refillQueue("playout-001");

		assertEquals("asset-queue-001", localMusic.getAssetId());
		assertEquals("/api/assets/audio/asset-queue-001.wav", localMusic.getAssetUrl());
		verify(assetService).ensureQueueAudioAsset(argThat(item -> "queue-001".equals(item.getId())));
	}

	@Test
	void tuneCreatesInitialWarmupQueueAndAutoStartsPlaybackWhenRequested() {
		ProgrammingService.ResolvedProgramPlan plan = new ProgrammingService.ResolvedProgramPlan(
				"tmpl-night-regular",
				3,
				"深夜の作業ノート",
				120_000,
				List.of(
						new ProgrammingService.ResolvedSlot("slot-1", SlotRole.OPENING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-2", SlotRole.TOPIC, com.seedshiftradio.domain.ConstraintMode.SOFT, 60_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-3", SlotRole.ENDING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.JINGLE)),
				false,
				List.of());

		AtomicReference<PlayoutSessionEntity> savedSession = new AtomicReference<>();
		Map<String, ProgramBlockEntity> blocksById = new HashMap<>();
		Map<String, List<ProgramBlockSlotEntity>> slotsByBlockId = new HashMap<>();
		List<QueueItemEntity> queueItems = new ArrayList<>();
		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station()));
		when(programmingService.resolveCurrentPlan(anyString(), any(OffsetDateTime.class))).thenReturn(plan);
		when(playoutSessionRepository.save(any(PlayoutSessionEntity.class))).thenAnswer(invocation -> {
			PlayoutSessionEntity session = invocation.getArgument(0);
			savedSession.set(session);
			return session;
		});
		when(playoutSessionRepository.findFirstByOrderByStartedAtDesc()).thenAnswer(invocation -> Optional.ofNullable(savedSession.get()));
		when(playoutSessionRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedSession.get()));
		when(programBlockRepository.save(any(ProgramBlockEntity.class))).thenAnswer(invocation -> {
			ProgramBlockEntity block = invocation.getArgument(0);
			blocksById.put(block.getId(), block);
			return block;
		});
		when(programBlockRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(blocksById.get(invocation.getArgument(0))));
		when(programBlockRepository.findTopBySessionIdOrderByStartedAtDesc(anyString())).thenAnswer(invocation -> blocksById.values().stream()
				.filter(block -> invocation.getArgument(0).equals(block.getSessionId()))
				.findFirst());
		when(programBlockSlotRepository.saveAll(any())).thenAnswer(invocation -> {
			List<ProgramBlockSlotEntity> slots = invocation.getArgument(0);
			for (ProgramBlockSlotEntity slot : slots) {
				slotsByBlockId.computeIfAbsent(slot.getProgramBlockId(), ignored -> new ArrayList<>()).removeIf(existing -> existing.getId().equals(slot.getId()));
				slotsByBlockId.computeIfAbsent(slot.getProgramBlockId(), ignored -> new ArrayList<>()).add(slot);
			}
			return slots;
		});
		when(programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> slotsByBlockId
				.getOrDefault(invocation.getArgument(0), List.of())
				.stream()
				.sorted(Comparator.comparing(ProgramBlockSlotEntity::getSequenceNo))
				.toList());
		when(programBlockSlotRepository.findById(anyString())).thenAnswer(invocation -> slotsByBlockId.values().stream()
				.flatMap((List<ProgramBlockSlotEntity> slotList) -> slotList.stream())
				.filter(slot -> slot.getId().equals(invocation.getArgument(0)))
				.findFirst());
		when(queueItemRepository.findBySessionIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.toList());
		when(queueItemRepository.findTopBySessionIdAndStatusOrderBySequenceNoAsc(anyString(), any(QueueItemStatus.class))).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.filter(item -> item.getStatus() == invocation.getArgument(1))
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.findFirst());
		when(queueItemRepository.saveAll(any())).thenAnswer(invocation -> {
			List<QueueItemEntity> items = invocation.getArgument(0);
			for (QueueItemEntity item : items) {
				replaceQueueItem(queueItems, item);
			}
			return items;
		});
		when(queueItemRepository.countBySessionIdAndStatus(anyString(), any(QueueItemStatus.class))).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.filter(item -> {
					QueueItemStatus status = invocation.getArgument(1);
					return item.getStatus() == status;
				})
				.count());
		TuneResponse response = radioService.tune(new TuneRequest("station-night", "test", true), "corr-001");
		assertEquals("station-night", response.stationId());
		assertEquals(PlayoutState.PREPARING, response.state());
		assertTrue(response.queueWarmupStarted());

		PlayoutSessionEntity session = savedSession.get();
		assertEquals("test", session.getRequestedBy());
		assertTrue(session.isResumePlayback());
		assertEquals(PlayoutState.PLAYING, session.getState());
		assertEquals(3, queueItems.size());
		assertEquals(2, queueItems.stream().filter(item -> item.getStatus() == QueueItemStatus.READY).count());
		assertEquals(1, queueItems.stream().filter(item -> item.getStatus() == QueueItemStatus.PLAYING).count());
		assertEquals(2, session.getBufferReadyCount());
		assertTrue(session.getCurrentQueueItemId() != null);
		assertEquals(queueItems.stream()
				.filter(item -> item.getStatus() == QueueItemStatus.PLAYING)
				.findFirst()
				.orElseThrow()
				.getId(), session.getCurrentQueueItemId());
		assertTrue(session.getCurrentProgramBlockId() != null && blocksById.containsKey(session.getCurrentProgramBlockId()));
		assertEquals(3, slotsByBlockId.get(session.getCurrentProgramBlockId()).size());
		assertTrue(queueItems.stream().map(QueueItemEntity::getProgramBlockId).distinct().count() == 1);
		assertEquals(QueueItemStatus.PLAYING, queueItems.stream()
				.min(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.orElseThrow()
				.getStatus());
	}

	@Test
	void tuneUsesConfiguredTargetReadyCountWhenBuildingInitialWarmupQueue() {
		when(settingsStore.load()).thenReturn(settingsDocument(new SettingsDocument.PlayoutSettings(4, 2, 90_000, 480_000, 2, 4, 3, 2, true)));
		ProgrammingService.ResolvedProgramPlan plan = new ProgrammingService.ResolvedProgramPlan(
				"tmpl-night-regular",
				3,
				"深夜の作業ノート",
				120_000,
				List.of(
						new ProgrammingService.ResolvedSlot("slot-1", SlotRole.OPENING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-2", SlotRole.TOPIC, com.seedshiftradio.domain.ConstraintMode.SOFT, 30_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-3", SlotRole.TOPIC, com.seedshiftradio.domain.ConstraintMode.SOFT, 30_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-4", SlotRole.ENDING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.JINGLE)),
				false,
				List.of());

		AtomicReference<PlayoutSessionEntity> savedSession = new AtomicReference<>();
		Map<String, ProgramBlockEntity> blocksById = new HashMap<>();
		Map<String, List<ProgramBlockSlotEntity>> slotsByBlockId = new HashMap<>();
		List<QueueItemEntity> queueItems = new ArrayList<>();
		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station()));
		when(programmingService.resolveCurrentPlan(anyString(), any(OffsetDateTime.class))).thenReturn(plan);
		when(playoutSessionRepository.save(any(PlayoutSessionEntity.class))).thenAnswer(invocation -> {
			PlayoutSessionEntity session = invocation.getArgument(0);
			savedSession.set(session);
			return session;
		});
		when(playoutSessionRepository.findFirstByOrderByStartedAtDesc()).thenAnswer(invocation -> Optional.ofNullable(savedSession.get()));
		when(playoutSessionRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedSession.get()));
		when(programBlockRepository.save(any(ProgramBlockEntity.class))).thenAnswer(invocation -> {
			ProgramBlockEntity block = invocation.getArgument(0);
			blocksById.put(block.getId(), block);
			return block;
		});
		when(programBlockRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(blocksById.get(invocation.getArgument(0))));
		when(programBlockRepository.findTopBySessionIdOrderByStartedAtDesc(anyString())).thenAnswer(invocation -> blocksById.values().stream()
				.filter(block -> invocation.getArgument(0).equals(block.getSessionId()))
				.findFirst());
		when(programBlockSlotRepository.saveAll(any())).thenAnswer(invocation -> {
			List<ProgramBlockSlotEntity> slots = invocation.getArgument(0);
			for (ProgramBlockSlotEntity slot : slots) {
				slotsByBlockId.computeIfAbsent(slot.getProgramBlockId(), ignored -> new ArrayList<>()).removeIf(existing -> existing.getId().equals(slot.getId()));
				slotsByBlockId.computeIfAbsent(slot.getProgramBlockId(), ignored -> new ArrayList<>()).add(slot);
			}
			return slots;
		});
		when(programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> slotsByBlockId
				.getOrDefault(invocation.getArgument(0), List.of())
				.stream()
				.sorted(Comparator.comparing(ProgramBlockSlotEntity::getSequenceNo))
				.toList());
		when(programBlockSlotRepository.findById(anyString())).thenAnswer(invocation -> slotsByBlockId.values().stream()
				.flatMap((List<ProgramBlockSlotEntity> slotList) -> slotList.stream())
				.filter(slot -> slot.getId().equals(invocation.getArgument(0)))
				.findFirst());
		when(queueItemRepository.findBySessionIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.toList());
		when(queueItemRepository.findTopBySessionIdAndStatusOrderBySequenceNoAsc(anyString(), any(QueueItemStatus.class))).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.filter(item -> item.getStatus() == invocation.getArgument(1))
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.findFirst());
		when(queueItemRepository.saveAll(any())).thenAnswer(invocation -> {
			List<QueueItemEntity> items = invocation.getArgument(0);
			for (QueueItemEntity item : items) {
				replaceQueueItem(queueItems, item);
			}
			return items;
		});
		when(queueItemRepository.countBySessionIdAndStatus(anyString(), any(QueueItemStatus.class))).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.filter(item -> {
					QueueItemStatus status = invocation.getArgument(1);
					return item.getStatus() == status;
				})
				.count());

		TuneResponse response = radioService.tune(new TuneRequest("station-night", "test", true), "corr-002");

		assertEquals("station-night", response.stationId());
		assertEquals(4, queueItems.size());
		assertEquals(3, queueItems.stream().filter(item -> item.getStatus() == QueueItemStatus.READY).count());
		assertEquals(1, queueItems.stream().filter(item -> item.getStatus() == QueueItemStatus.PLAYING).count());
		assertEquals(3, savedSession.get().getBufferReadyCount());
	}

	@Test
	void tuneRealTimeOnlyUsesMinimumReadyCountAndStationDurationCap() {
		when(settingsStore.load()).thenReturn(settingsDocument(new SettingsDocument.PlayoutSettings(4, 2, 90_000, 480_000, 3, 4, 3, 2, true)));
		StationProgrammingPolicyEntity realTimeOnlyPolicy = programmingPolicy("station-night", "REALTIME_ONLY", 1, 1);
		when(programmingPolicyRepository.findByStationId("station-night")).thenReturn(Optional.of(realTimeOnlyPolicy));
		ProgrammingService.ResolvedProgramPlan plan = new ProgrammingService.ResolvedProgramPlan(
				"tmpl-night-regular",
				3,
				"深夜の作業ノート",
				120_000,
				List.of(
						new ProgrammingService.ResolvedSlot("slot-1", SlotRole.OPENING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-2", SlotRole.TOPIC, com.seedshiftradio.domain.ConstraintMode.SOFT, 30_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-3", SlotRole.TOPIC, com.seedshiftradio.domain.ConstraintMode.SOFT, 30_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-4", SlotRole.ENDING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.JINGLE)),
				false,
				List.of());

		AtomicReference<PlayoutSessionEntity> savedSession = new AtomicReference<>();
		Map<String, ProgramBlockEntity> blocksById = new HashMap<>();
		Map<String, List<ProgramBlockSlotEntity>> slotsByBlockId = new HashMap<>();
		List<QueueItemEntity> queueItems = new ArrayList<>();
		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station()));
		when(programmingService.resolveCurrentPlan(anyString(), any(OffsetDateTime.class))).thenReturn(plan);
		when(playoutSessionRepository.save(any(PlayoutSessionEntity.class))).thenAnswer(invocation -> {
			PlayoutSessionEntity session = invocation.getArgument(0);
			savedSession.set(session);
			return session;
		});
		when(playoutSessionRepository.findFirstByOrderByStartedAtDesc()).thenAnswer(invocation -> Optional.ofNullable(savedSession.get()));
		when(playoutSessionRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedSession.get()));
		when(programBlockRepository.save(any(ProgramBlockEntity.class))).thenAnswer(invocation -> {
			ProgramBlockEntity block = invocation.getArgument(0);
			blocksById.put(block.getId(), block);
			return block;
		});
		when(programBlockRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(blocksById.get(invocation.getArgument(0))));
		when(programBlockRepository.findTopBySessionIdOrderByStartedAtDesc(anyString())).thenAnswer(invocation -> blocksById.values().stream()
				.filter(block -> invocation.getArgument(0).equals(block.getSessionId()))
				.findFirst());
		when(programBlockSlotRepository.saveAll(any())).thenAnswer(invocation -> {
			List<ProgramBlockSlotEntity> slots = invocation.getArgument(0);
			for (ProgramBlockSlotEntity slot : slots) {
				slotsByBlockId.computeIfAbsent(slot.getProgramBlockId(), ignored -> new ArrayList<>()).removeIf(existing -> existing.getId().equals(slot.getId()));
				slotsByBlockId.computeIfAbsent(slot.getProgramBlockId(), ignored -> new ArrayList<>()).add(slot);
			}
			return slots;
		});
		when(programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> slotsByBlockId
				.getOrDefault(invocation.getArgument(0), List.of())
				.stream()
				.sorted(Comparator.comparing(ProgramBlockSlotEntity::getSequenceNo))
				.toList());
		when(programBlockSlotRepository.findById(anyString())).thenAnswer(invocation -> slotsByBlockId.values().stream()
				.flatMap((List<ProgramBlockSlotEntity> slotList) -> slotList.stream())
				.filter(slot -> slot.getId().equals(invocation.getArgument(0)))
				.findFirst());
		when(queueItemRepository.findBySessionIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.toList());
		when(queueItemRepository.findTopBySessionIdAndStatusOrderBySequenceNoAsc(anyString(), any(QueueItemStatus.class))).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.filter(item -> item.getStatus() == invocation.getArgument(1))
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.findFirst());
		when(queueItemRepository.saveAll(any())).thenAnswer(invocation -> {
			List<QueueItemEntity> items = invocation.getArgument(0);
			for (QueueItemEntity item : items) {
				replaceQueueItem(queueItems, item);
			}
			return items;
		});
		when(queueItemRepository.countBySessionIdAndStatus(anyString(), any(QueueItemStatus.class))).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.filter(item -> {
					QueueItemStatus status = invocation.getArgument(1);
					return item.getStatus() == status;
				})
				.count());

		TuneResponse response = radioService.tune(new TuneRequest("station-night", "test", true), "corr-003");

		assertEquals("station-night", response.stationId());
		assertEquals(3, queueItems.size());
		assertEquals(2, queueItems.stream().filter(item -> item.getStatus() == QueueItemStatus.READY).count());
		assertEquals(1, queueItems.stream().filter(item -> item.getStatus() == QueueItemStatus.PLAYING).count());
	}

	@Test
	void tunePrefetchesSpokenAssetsWithinScriptAndTtsAheadCounts() {
		when(settingsStore.load()).thenReturn(settingsDocument(new SettingsDocument.PlayoutSettings(4, 2, 90_000, 480_000, 2, 3, 0, 2, true)));
		ProgrammingService.ResolvedProgramPlan plan = new ProgrammingService.ResolvedProgramPlan(
				"tmpl-night-regular",
				3,
				"深夜の作業ノート",
				120_000,
				List.of(
						new ProgrammingService.ResolvedSlot("slot-1", SlotRole.OPENING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-2", SlotRole.TOPIC, com.seedshiftradio.domain.ConstraintMode.SOFT, 30_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-3", SlotRole.TOPIC, com.seedshiftradio.domain.ConstraintMode.SOFT, 30_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-4", SlotRole.ENDING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.JINGLE)),
				false,
				List.of());

		AtomicReference<PlayoutSessionEntity> savedSession = new AtomicReference<>();
		Map<String, ProgramBlockEntity> blocksById = new HashMap<>();
		Map<String, List<ProgramBlockSlotEntity>> slotsByBlockId = new HashMap<>();
		List<QueueItemEntity> queueItems = new ArrayList<>();
		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station()));
		when(programmingService.resolveCurrentPlan(anyString(), any(OffsetDateTime.class))).thenReturn(plan);
		when(playoutSessionRepository.save(any(PlayoutSessionEntity.class))).thenAnswer(invocation -> {
			PlayoutSessionEntity session = invocation.getArgument(0);
			savedSession.set(session);
			return session;
		});
		when(playoutSessionRepository.findFirstByOrderByStartedAtDesc()).thenAnswer(invocation -> Optional.ofNullable(savedSession.get()));
		when(playoutSessionRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedSession.get()));
		when(programBlockRepository.save(any(ProgramBlockEntity.class))).thenAnswer(invocation -> {
			ProgramBlockEntity block = invocation.getArgument(0);
			blocksById.put(block.getId(), block);
			return block;
		});
		when(programBlockRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(blocksById.get(invocation.getArgument(0))));
		when(programBlockRepository.findTopBySessionIdOrderByStartedAtDesc(anyString())).thenAnswer(invocation -> blocksById.values().stream()
				.filter(block -> invocation.getArgument(0).equals(block.getSessionId()))
				.findFirst());
		when(programBlockSlotRepository.saveAll(any())).thenAnswer(invocation -> {
			List<ProgramBlockSlotEntity> slots = invocation.getArgument(0);
			for (ProgramBlockSlotEntity slot : slots) {
				slotsByBlockId.computeIfAbsent(slot.getProgramBlockId(), ignored -> new ArrayList<>()).removeIf(existing -> existing.getId().equals(slot.getId()));
				slotsByBlockId.computeIfAbsent(slot.getProgramBlockId(), ignored -> new ArrayList<>()).add(slot);
			}
			return slots;
		});
		when(programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> slotsByBlockId
				.getOrDefault(invocation.getArgument(0), List.of())
				.stream()
				.sorted(Comparator.comparing(ProgramBlockSlotEntity::getSequenceNo))
				.toList());
		when(programBlockSlotRepository.findById(anyString())).thenAnswer(invocation -> slotsByBlockId.values().stream()
				.flatMap((List<ProgramBlockSlotEntity> slotList) -> slotList.stream())
				.filter(slot -> slot.getId().equals(invocation.getArgument(0)))
				.findFirst());
		when(queueItemRepository.findBySessionIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.toList());
		when(queueItemRepository.findTopBySessionIdAndStatusOrderBySequenceNoAsc(anyString(), any(QueueItemStatus.class))).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.filter(item -> item.getStatus() == invocation.getArgument(1))
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.findFirst());
		when(queueItemRepository.saveAll(any())).thenAnswer(invocation -> {
			List<QueueItemEntity> items = invocation.getArgument(0);
			for (QueueItemEntity item : items) {
				replaceQueueItem(queueItems, item);
			}
			return items;
		});
		when(queueItemRepository.countBySessionIdAndStatus(anyString(), any(QueueItemStatus.class))).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.filter(item -> {
					QueueItemStatus status = invocation.getArgument(1);
					return item.getStatus() == status;
				})
				.count());
		doAnswer(invocation -> {
			QueueItemEntity item = invocation.getArgument(0);
			item.setAssetId("asset-" + item.getId());
			item.setAssetUrl("/api/assets/audio/asset-" + item.getId() + ".wav");
			return null;
		}).when(assetService).ensureQueueAudioAsset(any(QueueItemEntity.class));
		when(scriptGenerationService.ensureScriptAsset(any(QueueItemEntity.class))).thenReturn(scriptSnapshot());

		TuneResponse response = radioService.tune(new TuneRequest("station-night", "test", false), "corr-004");

		assertEquals("station-night", response.stationId());
		assertEquals(4, queueItems.size());
		assertEquals(4L, queueItems.stream().filter(item -> item.getStatus() == QueueItemStatus.READY).count());
		assertEquals(1L, queueItems.stream().filter(item -> item.getAssetId() != null && !item.getAssetId().isBlank()).count());
		assertTrue(queueItems.stream().anyMatch(item -> item.getSequenceNo() == 1 && item.getAssetId() != null));
		assertTrue(queueItems.stream().anyMatch(item -> item.getSequenceNo() == 2 && item.getAssetId() == null));
		assertTrue(queueItems.stream().anyMatch(item -> item.getSequenceNo() == 3 && item.getAssetId() == null));
		verify(assetService).ensureQueueAudioAsset(any(QueueItemEntity.class));
		verify(scriptGenerationService, org.mockito.Mockito.times(2)).ensureScriptAsset(any(QueueItemEntity.class));
	}

	@Test
	void tuneLimitsIdlePrefetchWhenManualPlaybackIsWaiting() {
		when(settingsStore.load()).thenReturn(settingsDocument(new SettingsDocument.PlayoutSettings(4, 2, 90_000, 480_000, 2, 3, 2, 2, false)));
		ProgrammingService.ResolvedProgramPlan plan = new ProgrammingService.ResolvedProgramPlan(
				"tmpl-night-regular",
				3,
				"深夜の作業ノート",
				120_000,
				List.of(
						new ProgrammingService.ResolvedSlot("slot-1", SlotRole.OPENING, com.seedshiftradio.domain.ConstraintMode.HARD, 45_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-2", SlotRole.TOPIC, com.seedshiftradio.domain.ConstraintMode.SOFT, 45_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-3", SlotRole.MUSIC_BREAK, com.seedshiftradio.domain.ConstraintMode.SOFT, 30_000, SegmentType.MUSIC_AI),
						new ProgrammingService.ResolvedSlot("slot-4", SlotRole.MUSIC_BREAK, com.seedshiftradio.domain.ConstraintMode.SOFT, 30_000, SegmentType.MUSIC_AI)),
				false,
				List.of());

		AtomicReference<PlayoutSessionEntity> savedSession = new AtomicReference<>();
		Map<String, ProgramBlockEntity> blocksById = new HashMap<>();
		Map<String, List<ProgramBlockSlotEntity>> slotsByBlockId = new HashMap<>();
		List<QueueItemEntity> queueItems = new ArrayList<>();
		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station()));
		when(programmingService.resolveCurrentPlan(anyString(), any(OffsetDateTime.class))).thenReturn(plan);
		when(playoutSessionRepository.save(any(PlayoutSessionEntity.class))).thenAnswer(invocation -> {
			PlayoutSessionEntity session = invocation.getArgument(0);
			savedSession.set(session);
			return session;
		});
		when(playoutSessionRepository.findFirstByOrderByStartedAtDesc()).thenAnswer(invocation -> Optional.ofNullable(savedSession.get()));
		when(playoutSessionRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedSession.get()));
		when(programBlockRepository.save(any(ProgramBlockEntity.class))).thenAnswer(invocation -> {
			ProgramBlockEntity block = invocation.getArgument(0);
			blocksById.put(block.getId(), block);
			return block;
		});
		when(programBlockRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(blocksById.get(invocation.getArgument(0))));
		when(programBlockRepository.findTopBySessionIdOrderByStartedAtDesc(anyString())).thenAnswer(invocation -> blocksById.values().stream()
				.filter(block -> invocation.getArgument(0).equals(block.getSessionId()))
				.findFirst());
		when(programBlockSlotRepository.saveAll(any())).thenAnswer(invocation -> {
			List<ProgramBlockSlotEntity> slots = invocation.getArgument(0);
			for (ProgramBlockSlotEntity slot : slots) {
				slotsByBlockId.computeIfAbsent(slot.getProgramBlockId(), ignored -> new ArrayList<>()).removeIf(existing -> existing.getId().equals(slot.getId()));
				slotsByBlockId.computeIfAbsent(slot.getProgramBlockId(), ignored -> new ArrayList<>()).add(slot);
			}
			return slots;
		});
		when(programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> slotsByBlockId
				.getOrDefault(invocation.getArgument(0), List.of())
				.stream()
				.sorted(Comparator.comparing(ProgramBlockSlotEntity::getSequenceNo))
				.toList());
		when(programBlockSlotRepository.findById(anyString())).thenAnswer(invocation -> slotsByBlockId.values().stream()
				.flatMap((List<ProgramBlockSlotEntity> slotList) -> slotList.stream())
				.filter(slot -> slot.getId().equals(invocation.getArgument(0)))
				.findFirst());
		when(queueItemRepository.findBySessionIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.toList());
		when(queueItemRepository.findTopBySessionIdAndStatusOrderBySequenceNoAsc(anyString(), any(QueueItemStatus.class))).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.filter(item -> item.getStatus() == invocation.getArgument(1))
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.findFirst());
		when(queueItemRepository.save(any(QueueItemEntity.class))).thenAnswer(invocation -> {
			QueueItemEntity item = invocation.getArgument(0);
			replaceQueueItem(queueItems, item);
			return item;
		});
		when(queueItemRepository.saveAll(any())).thenAnswer(invocation -> {
			List<QueueItemEntity> items = invocation.getArgument(0);
			for (QueueItemEntity item : items) {
				replaceQueueItem(queueItems, item);
			}
			return items;
		});
		when(queueItemRepository.countBySessionIdAndStatus(anyString(), any(QueueItemStatus.class))).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.filter(item -> {
					QueueItemStatus status = invocation.getArgument(1);
					return item.getStatus() == status;
				})
				.count());
		doAnswer(invocation -> {
			QueueItemEntity item = invocation.getArgument(0);
			item.setAssetId("asset-" + item.getId());
			item.setAssetUrl("/api/assets/audio/asset-" + item.getId() + ".wav");
			return null;
		}).when(assetService).ensureQueueAudioAsset(any(QueueItemEntity.class));
		when(scriptGenerationService.ensureScriptAsset(any(QueueItemEntity.class))).thenReturn(scriptSnapshot());

		TuneResponse response = radioService.tune(new TuneRequest("station-night", "test", false), "corr-004-idle");

		assertEquals("station-night", response.stationId());
		assertEquals(4, queueItems.size());
		assertEquals(2L, queueItems.stream().filter(item -> item.getStatus() == QueueItemStatus.READY).count());
		assertEquals(1L, queueItems.stream().filter(item -> item.getSegmentType() == SegmentType.MUSIC_AI && item.getStatus() == QueueItemStatus.GENERATING).count());
		assertEquals(1L, queueItems.stream().filter(item -> item.getSegmentType() == SegmentType.MUSIC_AI && item.getStatus() == QueueItemStatus.PLANNED).count());
		assertEquals(1L, queueItems.stream().filter(item -> item.getAssetId() != null && !item.getAssetId().isBlank()).count());
		verify(assetService).ensureQueueAudioAsset(any(QueueItemEntity.class));
		verify(scriptGenerationService, never()).ensureScriptAsset(any(QueueItemEntity.class));
		verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof GenerateMusicRequested));
	}

	@Test
	void tuneKeepsOnlyFirstPendingMusicGenerationWhenMusicAheadCountIsZero() {
		when(settingsStore.load()).thenReturn(settingsDocument(new SettingsDocument.PlayoutSettings(3, 2, 90_000, 480_000, 2, 4, 3, 0, true)));
		ProgrammingService.ResolvedProgramPlan plan = new ProgrammingService.ResolvedProgramPlan(
				"tmpl-night-regular",
				3,
				"深夜の作業ノート",
				120_000,
				List.of(
						new ProgrammingService.ResolvedSlot("slot-1", SlotRole.OPENING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.TALK),
						new ProgrammingService.ResolvedSlot("slot-2", SlotRole.MUSIC_BREAK, com.seedshiftradio.domain.ConstraintMode.SOFT, 30_000, SegmentType.MUSIC_AI),
						new ProgrammingService.ResolvedSlot("slot-3", SlotRole.MUSIC_BREAK, com.seedshiftradio.domain.ConstraintMode.SOFT, 30_000, SegmentType.MUSIC_AI),
						new ProgrammingService.ResolvedSlot("slot-4", SlotRole.ENDING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.JINGLE)),
				false,
				List.of());

		AtomicReference<PlayoutSessionEntity> savedSession = new AtomicReference<>();
		Map<String, ProgramBlockEntity> blocksById = new HashMap<>();
		Map<String, List<ProgramBlockSlotEntity>> slotsByBlockId = new HashMap<>();
		List<QueueItemEntity> queueItems = new ArrayList<>();
		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station()));
		when(programmingService.resolveCurrentPlan(anyString(), any(OffsetDateTime.class))).thenReturn(plan);
		when(playoutSessionRepository.save(any(PlayoutSessionEntity.class))).thenAnswer(invocation -> {
			PlayoutSessionEntity session = invocation.getArgument(0);
			savedSession.set(session);
			return session;
		});
		when(playoutSessionRepository.findFirstByOrderByStartedAtDesc()).thenAnswer(invocation -> Optional.ofNullable(savedSession.get()));
		when(playoutSessionRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedSession.get()));
		when(programBlockRepository.save(any(ProgramBlockEntity.class))).thenAnswer(invocation -> {
			ProgramBlockEntity block = invocation.getArgument(0);
			blocksById.put(block.getId(), block);
			return block;
		});
		when(programBlockRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(blocksById.get(invocation.getArgument(0))));
		when(programBlockRepository.findTopBySessionIdOrderByStartedAtDesc(anyString())).thenAnswer(invocation -> blocksById.values().stream()
				.filter(block -> invocation.getArgument(0).equals(block.getSessionId()))
				.findFirst());
		when(programBlockSlotRepository.saveAll(any())).thenAnswer(invocation -> {
			List<ProgramBlockSlotEntity> slots = invocation.getArgument(0);
			for (ProgramBlockSlotEntity slot : slots) {
				slotsByBlockId.computeIfAbsent(slot.getProgramBlockId(), ignored -> new ArrayList<>()).removeIf(existing -> existing.getId().equals(slot.getId()));
				slotsByBlockId.computeIfAbsent(slot.getProgramBlockId(), ignored -> new ArrayList<>()).add(slot);
			}
			return slots;
		});
		when(programBlockSlotRepository.findByProgramBlockIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> slotsByBlockId
				.getOrDefault(invocation.getArgument(0), List.of())
				.stream()
				.sorted(Comparator.comparing(ProgramBlockSlotEntity::getSequenceNo))
				.toList());
		when(programBlockSlotRepository.findById(anyString())).thenAnswer(invocation -> slotsByBlockId.values().stream()
				.flatMap((List<ProgramBlockSlotEntity> slotList) -> slotList.stream())
				.filter(slot -> slot.getId().equals(invocation.getArgument(0)))
				.findFirst());
		when(queueItemRepository.findBySessionIdOrderBySequenceNoAsc(anyString())).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.toList());
		when(queueItemRepository.findTopBySessionIdAndStatusOrderBySequenceNoAsc(anyString(), any(QueueItemStatus.class))).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.filter(item -> item.getStatus() == invocation.getArgument(1))
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.findFirst());
		when(queueItemRepository.save(any(QueueItemEntity.class))).thenAnswer(invocation -> {
			QueueItemEntity item = invocation.getArgument(0);
			replaceQueueItem(queueItems, item);
			return item;
		});
		when(queueItemRepository.saveAll(any())).thenAnswer(invocation -> {
			List<QueueItemEntity> items = invocation.getArgument(0);
			for (QueueItemEntity item : items) {
				replaceQueueItem(queueItems, item);
			}
			return items;
		});
		when(queueItemRepository.countBySessionIdAndStatus(anyString(), any(QueueItemStatus.class))).thenAnswer(invocation -> queueItems.stream()
				.filter(item -> savedSession.get() != null && savedSession.get().getId().equals(invocation.getArgument(0)))
				.filter(item -> {
					QueueItemStatus status = invocation.getArgument(1);
					return item.getStatus() == status;
				})
				.count());

		radioService.tune(new TuneRequest("station-night", "test", true), "corr-004");

		assertEquals(4, queueItems.size());
		assertEquals(1L, queueItems.stream().filter(item -> item.getSegmentType() == SegmentType.MUSIC_AI && item.getStatus() == QueueItemStatus.GENERATING).count());
		assertEquals(1L, queueItems.stream().filter(item -> item.getSegmentType() == SegmentType.MUSIC_AI && item.getStatus() == QueueItemStatus.PLANNED).count());
		assertEquals(1L, queueItems.stream().filter(item -> item.getStatus() == QueueItemStatus.PLAYING).count());
		assertEquals(1L, queueItems.stream().filter(item -> item.getStatus() == QueueItemStatus.READY).count());
		verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof GenerateMusicRequested));
	}

	@Test
	void refillQueueAdvancesSpokenPrefetchWindowWithoutChangingReadyCounts() {
		when(settingsStore.load()).thenReturn(settingsDocument(new SettingsDocument.PlayoutSettings(4, 2, 90_000, 480_000, 2, 3, 2, 2, false)));
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-001");
		session.setCurrentProgramBlockId("block-current");
		ProgramBlockEntity currentBlock = programBlock("block-current", "playout-001", ProgramBlockStatus.ACTIVE);
		List<ProgramBlockSlotEntity> currentSlots = new ArrayList<>(List.of(
				blockSlot("slot-1", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.OPENING, 30_000),
				blockSlot("slot-2", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.TOPIC, 30_000),
				blockSlot("slot-3", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.TOPIC, 30_000),
				blockSlot("slot-4", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.ENDING, 30_000)));
		QueueItemEntity current = queueItem("queue-001", "playout-001", QueueItemStatus.PLAYING);
		current.setProgramBlockId("block-current");
		current.setProgramSlotId("slot-1");
		current.setAssetId("asset-queue-001");
		QueueItemEntity nextReady = queueItem("queue-002", "playout-001", QueueItemStatus.READY);
		nextReady.setSequenceNo(2);
		nextReady.setProgramBlockId("block-current");
		nextReady.setProgramSlotId("slot-2");
		nextReady.setAssetId("asset-queue-002");
		QueueItemEntity nextAudioPending = queueItem("queue-003", "playout-001", QueueItemStatus.READY);
		nextAudioPending.setSequenceNo(3);
		nextAudioPending.setProgramBlockId("block-current");
		nextAudioPending.setProgramSlotId("slot-3");
		nextAudioPending.setAssetId(null);
		nextAudioPending.setAssetUrl(null);
		QueueItemEntity nextScriptPending = queueItem("queue-004", "playout-001", QueueItemStatus.READY);
		nextScriptPending.setSequenceNo(4);
		nextScriptPending.setProgramBlockId("block-current");
		nextScriptPending.setProgramSlotId("slot-4");
		nextScriptPending.setAssetId(null);
		nextScriptPending.setAssetUrl(null);
		List<QueueItemEntity> queueItems = new ArrayList<>(List.of(current, nextReady, nextAudioPending, nextScriptPending));

		wireRepositoryState(session, List.of(currentBlock), Map.of("block-current", currentSlots), queueItems);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		doAnswer(invocation -> {
			QueueItemEntity item = invocation.getArgument(0);
			item.setAssetId("asset-" + item.getId());
			item.setAssetUrl("/api/assets/audio/asset-" + item.getId() + ".wav");
			return null;
		}).when(assetService).ensureQueueAudioAsset(any(QueueItemEntity.class));
		when(scriptGenerationService.ensureScriptAsset(any(QueueItemEntity.class))).thenReturn(scriptSnapshot());

		radioService.refillQueue("playout-001");

		List<QueueItemEntity> updatedItems = queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001");
		assertEquals(3L, updatedItems.stream().filter(item -> item.getStatus() == QueueItemStatus.READY).count());
		assertTrue(updatedItems.stream().anyMatch(item -> "queue-003".equals(item.getId()) && item.getAssetId() != null));
		assertTrue(updatedItems.stream().anyMatch(item -> "queue-004".equals(item.getId()) && item.getAssetId() == null));
		verify(assetService).ensureQueueAudioAsset(argThat(item -> "queue-003".equals(item.getId())));
		verify(scriptGenerationService).ensureScriptAsset(argThat(item -> "queue-004".equals(item.getId())));
	}

	@Test
	void refillQueuePrefetchesOnlyFirstCurrentBlockLetterAndSkipsLaterLetters() {
		when(settingsStore.load()).thenReturn(settingsDocument(new SettingsDocument.PlayoutSettings(4, 2, 90_000, 480_000, 2, 4, 2, 2, false)));
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-001");
		session.setCurrentProgramBlockId("block-current");
		ProgramBlockEntity currentBlock = programBlock("block-current", "playout-001", ProgramBlockStatus.ACTIVE);
		ProgramBlockEntity nextBlock = programBlock("block-next", "playout-001", ProgramBlockStatus.PLANNED);
		nextBlock.setStartedAt(Instant.parse("2026-03-20T09:01:00Z"));
		List<ProgramBlockSlotEntity> currentSlots = new ArrayList<>(List.of(
				blockSlot("slot-1", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.OPENING, 30_000),
				blockSlot("slot-2", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.TOPIC, 30_000),
				blockSlot("slot-3", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.LETTER, 30_000),
				blockSlot("slot-4", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.LETTER, 30_000)));
		QueueItemEntity current = queueItem("queue-001", "playout-001", QueueItemStatus.PLAYING);
		current.setProgramBlockId("block-current");
		current.setProgramSlotId("slot-1");
		current.setAssetId("asset-queue-001");
		QueueItemEntity nextReady = queueItem("queue-002", "playout-001", QueueItemStatus.READY);
		nextReady.setSequenceNo(2);
		nextReady.setProgramBlockId("block-current");
		nextReady.setProgramSlotId("slot-2");
		nextReady.setAssetId("asset-queue-002");
		QueueItemEntity currentBlockLetter = queueItem("queue-003", "playout-001", QueueItemStatus.READY);
		currentBlockLetter.setSequenceNo(3);
		currentBlockLetter.setProgramBlockId("block-current");
		currentBlockLetter.setProgramSlotId("slot-3");
		currentBlockLetter.setSegmentType(SegmentType.LETTER);
		currentBlockLetter.setSlotRole(SlotRole.LETTER);
		currentBlockLetter.setAssetId(null);
		currentBlockLetter.setAssetUrl(null);
		QueueItemEntity laterCurrentBlockLetter = queueItem("queue-004", "playout-001", QueueItemStatus.READY);
		laterCurrentBlockLetter.setSequenceNo(4);
		laterCurrentBlockLetter.setProgramBlockId("block-current");
		laterCurrentBlockLetter.setProgramSlotId("slot-4");
		laterCurrentBlockLetter.setSegmentType(SegmentType.LETTER);
		laterCurrentBlockLetter.setSlotRole(SlotRole.LETTER);
		laterCurrentBlockLetter.setAssetId(null);
		laterCurrentBlockLetter.setAssetUrl(null);
		QueueItemEntity nextBlockTalk = queueItem("queue-005", "playout-001", QueueItemStatus.READY);
		nextBlockTalk.setSequenceNo(5);
		nextBlockTalk.setProgramBlockId("block-next");
		nextBlockTalk.setProgramSlotId("slot-5");
		nextBlockTalk.setAssetId(null);
		nextBlockTalk.setAssetUrl(null);
		QueueItemEntity nextBlockLetter = queueItem("queue-006", "playout-001", QueueItemStatus.READY);
		nextBlockLetter.setSequenceNo(6);
		nextBlockLetter.setProgramBlockId("block-next");
		nextBlockLetter.setProgramSlotId("slot-6");
		nextBlockLetter.setSegmentType(SegmentType.LETTER);
		nextBlockLetter.setSlotRole(SlotRole.LETTER);
		nextBlockLetter.setAssetId(null);
		nextBlockLetter.setAssetUrl(null);
		List<QueueItemEntity> queueItems = new ArrayList<>(List.of(
				current,
				nextReady,
				currentBlockLetter,
				laterCurrentBlockLetter,
				nextBlockTalk,
				nextBlockLetter));

		wireRepositoryState(session, List.of(currentBlock, nextBlock), Map.of("block-current", currentSlots), queueItems);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		doAnswer(invocation -> {
			QueueItemEntity item = invocation.getArgument(0);
			item.setAssetId("asset-" + item.getId());
			item.setAssetUrl("/api/assets/audio/asset-" + item.getId() + ".wav");
			return null;
		}).when(assetService).ensureQueueAudioAsset(any(QueueItemEntity.class));
		when(scriptGenerationService.ensureScriptAsset(any(QueueItemEntity.class))).thenReturn(scriptSnapshot());

		radioService.refillQueue("playout-001");

		List<QueueItemEntity> updatedItems = queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001");
		assertEquals(5L, updatedItems.stream().filter(item -> item.getStatus() == QueueItemStatus.READY).count());
		assertTrue(updatedItems.stream().anyMatch(item -> "queue-003".equals(item.getId()) && item.getAssetId() != null));
		assertTrue(updatedItems.stream().anyMatch(item -> "queue-004".equals(item.getId()) && item.getAssetId() == null));
		assertTrue(updatedItems.stream().anyMatch(item -> "queue-005".equals(item.getId()) && item.getAssetId() == null));
		assertTrue(updatedItems.stream().anyMatch(item -> "queue-006".equals(item.getId()) && item.getAssetId() == null));
		verify(assetService).ensureQueueAudioAsset(argThat(item -> "queue-003".equals(item.getId())));
		verify(assetService, never()).ensureQueueAudioAsset(argThat(item -> "queue-004".equals(item.getId())));
		verify(assetService, never()).ensureQueueAudioAsset(argThat(item -> "queue-006".equals(item.getId())));
		verify(scriptGenerationService).ensureScriptAsset(argThat(item -> "queue-005".equals(item.getId())));
		verify(scriptGenerationService, never()).ensureScriptAsset(argThat(item -> "queue-004".equals(item.getId())));
		verify(scriptGenerationService, never()).ensureScriptAsset(argThat(item -> "queue-006".equals(item.getId())));
	}

	@Test
	void synchronizeSessionAfterAsyncUpdateStartsNextPlannedMusicWhenAheadCapacityAllows() {
		when(settingsStore.load()).thenReturn(settingsDocument(new SettingsDocument.PlayoutSettings(3, 2, 90_000, 480_000, 2, 4, 3, 2, true)));
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PREPARING, null);
		QueueItemEntity spoken = queueItem("queue-001", "playout-001", QueueItemStatus.READY);
		spoken.setSequenceNo(1);
		QueueItemEntity readyMusic = queueItem("queue-002", "playout-001", QueueItemStatus.READY);
		readyMusic.setSequenceNo(2);
		readyMusic.setSegmentType(SegmentType.MUSIC_AI);
		readyMusic.setSlotRole(SlotRole.MUSIC_BREAK);
		readyMusic.setAssetId("asset-ready");
		readyMusic.setAssetUrl("/api/assets/audio/asset-ready.wav");
		QueueItemEntity plannedMusic = queueItem("queue-003", "playout-001", QueueItemStatus.PLANNED);
		plannedMusic.setSequenceNo(3);
		plannedMusic.setSegmentType(SegmentType.MUSIC_AI);
		plannedMusic.setSlotRole(SlotRole.MUSIC_BREAK);
		List<QueueItemEntity> queueItems = new ArrayList<>(List.of(spoken, readyMusic, plannedMusic));

		wireRepositoryState(session, List.of(), Map.of(), queueItems);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));

		radioService.synchronizeSessionAfterAsyncUpdate("playout-001");

		QueueItemEntity promoted = queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001").stream()
				.filter(item -> "queue-003".equals(item.getId()))
				.findFirst()
				.orElseThrow();
		assertEquals(QueueItemStatus.GENERATING, promoted.getStatus());
		verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof GenerateMusicRequested requested
				&& "queue-003".equals(requested.queueItemId())));
	}

	@Test
	void synchronizeSessionAfterAsyncUpdatePromotesCurrentAndOnlyFirstNextBlockMusicInAssistedMode() {
		when(settingsStore.load()).thenReturn(settingsDocument(new SettingsDocument.PlayoutSettings(3, 2, 90_000, 480_000, 2, 4, 3, 3, true)));
		StationProgrammingPolicyEntity assistedPolicy = programmingPolicy("station-night", "ASSISTED", 4, 2);
		when(programmingPolicyRepository.findByStationId("station-night")).thenReturn(Optional.of(assistedPolicy));
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-001");
		session.setCurrentProgramBlockId("block-current");
		ProgramBlockEntity currentBlock = programBlock("block-current", "playout-001", ProgramBlockStatus.ACTIVE);
		ProgramBlockEntity nextBlock = programBlock("block-next", "playout-001", ProgramBlockStatus.PLANNED);
		nextBlock.setStartedAt(Instant.parse("2026-03-20T09:01:00Z"));
		QueueItemEntity current = queueItem("queue-001", "playout-001", QueueItemStatus.PLAYING);
		current.setProgramBlockId("block-current");
		current.setProgramSlotId("slot-1");
		current.setAssetId("asset-queue-001");
		QueueItemEntity currentBlockMusic = queueItem("queue-002", "playout-001", QueueItemStatus.PLANNED);
		currentBlockMusic.setSequenceNo(2);
		currentBlockMusic.setProgramBlockId("block-current");
		currentBlockMusic.setProgramSlotId("slot-2");
		currentBlockMusic.setSegmentType(SegmentType.MUSIC_AI);
		currentBlockMusic.setSlotRole(SlotRole.MUSIC_BREAK);
		currentBlockMusic.setAssetId(null);
		QueueItemEntity nextBlockMusic = queueItem("queue-003", "playout-001", QueueItemStatus.PLANNED);
		nextBlockMusic.setSequenceNo(3);
		nextBlockMusic.setProgramBlockId("block-next");
		nextBlockMusic.setProgramSlotId("slot-3");
		nextBlockMusic.setSegmentType(SegmentType.MUSIC_AI);
		nextBlockMusic.setSlotRole(SlotRole.MUSIC_BREAK);
		nextBlockMusic.setAssetId(null);
		QueueItemEntity secondNextBlockMusic = queueItem("queue-004", "playout-001", QueueItemStatus.PLANNED);
		secondNextBlockMusic.setSequenceNo(4);
		secondNextBlockMusic.setProgramBlockId("block-next");
		secondNextBlockMusic.setProgramSlotId("slot-4");
		secondNextBlockMusic.setSegmentType(SegmentType.MUSIC_AI);
		secondNextBlockMusic.setSlotRole(SlotRole.MUSIC_BREAK);
		secondNextBlockMusic.setAssetId(null);
		List<QueueItemEntity> queueItems = new ArrayList<>(List.of(current, currentBlockMusic, nextBlockMusic, secondNextBlockMusic));

		wireRepositoryState(session, List.of(currentBlock, nextBlock), Map.of(), queueItems);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));

		radioService.synchronizeSessionAfterAsyncUpdate("playout-001");

		List<QueueItemEntity> updatedItems = queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001");
		assertEquals(1L, updatedItems.stream().filter(item -> item.getStatus() == QueueItemStatus.PLAYING).count());
		assertEquals(2L, updatedItems.stream().filter(item -> item.getStatus() == QueueItemStatus.GENERATING).count());
		assertTrue(updatedItems.stream().anyMatch(item -> "queue-002".equals(item.getId()) && item.getStatus() == QueueItemStatus.GENERATING));
		assertTrue(updatedItems.stream().anyMatch(item -> "queue-003".equals(item.getId()) && item.getStatus() == QueueItemStatus.GENERATING));
		assertTrue(updatedItems.stream().anyMatch(item -> "queue-004".equals(item.getId()) && item.getStatus() == QueueItemStatus.PLANNED));
	}

	@Test
	void synchronizeSessionAfterAsyncUpdateDoesNotPromoteMusicWhenAheadCapacityIsFull() {
		when(settingsStore.load()).thenReturn(settingsDocument(new SettingsDocument.PlayoutSettings(3, 2, 90_000, 480_000, 2, 4, 3, 1, true)));
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-001");
		session.setCurrentProgramBlockId("block-current");
		ProgramBlockEntity currentBlock = programBlock("block-current", "playout-001", ProgramBlockStatus.ACTIVE);
		QueueItemEntity current = queueItem("queue-001", "playout-001", QueueItemStatus.PLAYING);
		current.setProgramBlockId("block-current");
		current.setProgramSlotId("slot-1");
		current.setSegmentType(SegmentType.MUSIC_AI);
		current.setSlotRole(SlotRole.MUSIC_BREAK);
		current.setAssetId("asset-queue-001");
		QueueItemEntity plannedMusic = queueItem("queue-002", "playout-001", QueueItemStatus.PLANNED);
		plannedMusic.setSequenceNo(2);
		plannedMusic.setProgramBlockId("block-current");
		plannedMusic.setProgramSlotId("slot-2");
		plannedMusic.setSegmentType(SegmentType.MUSIC_AI);
		plannedMusic.setSlotRole(SlotRole.MUSIC_BREAK);
		plannedMusic.setAssetId(null);
		List<QueueItemEntity> queueItems = new ArrayList<>(List.of(current, plannedMusic));

		wireRepositoryState(session, List.of(currentBlock), Map.of(), queueItems);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));

		radioService.synchronizeSessionAfterAsyncUpdate("playout-001");

		List<QueueItemEntity> updatedItems = queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001");
		assertEquals(1L, updatedItems.stream().filter(item -> item.getStatus() == QueueItemStatus.PLAYING).count());
		assertTrue(updatedItems.stream().anyMatch(item -> "queue-002".equals(item.getId()) && item.getStatus() == QueueItemStatus.PLANNED));
	}

	@Test
	void handleAsyncGenerationFailureConvertsMusicToFallbackAndAutoStartsWhenResumePlaybackIsEnabled() {
		StationProgrammingPolicyEntity realTimeOnlyPolicy = programmingPolicy("station-night", "REALTIME_ONLY", 1, 1);
		when(programmingPolicyRepository.findByStationId("station-night")).thenReturn(Optional.of(realTimeOnlyPolicy));
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PREPARING, null);
		session.setResumePlayback(true);
		session.setCurrentProgramBlockId("block-current");
		ProgramBlockEntity currentBlock = programBlock("block-current", "playout-001", ProgramBlockStatus.ACTIVE);
		ProgramBlockSlotEntity musicSlot = blockSlot("slot-1", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.MUSIC_BREAK, 45_000);
		musicSlot.setResolvedSegmentType(SegmentType.MUSIC_AI);
		QueueItemEntity failedMusic = queueItem("queue-001", "playout-001", QueueItemStatus.GENERATING);
		failedMusic.setProgramBlockId("block-current");
		failedMusic.setProgramSlotId("slot-1");
		failedMusic.setSegmentType(SegmentType.MUSIC_AI);
		failedMusic.setSlotRole(SlotRole.MUSIC_BREAK);
		failedMusic.setTitle("AIミュージック");
		failedMusic.setAssetId(null);
		failedMusic.setAssetUrl(null);
		List<QueueItemEntity> queueItems = new ArrayList<>(List.of(failedMusic));

		wireRepositoryState(session, List.of(currentBlock), Map.of("block-current", new ArrayList<>(List.of(musicSlot))), queueItems);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		when(assetService.prepareMusicFailureFallback(failedMusic, "PROVIDER_TIMEOUT")).thenReturn(new AssetService.MusicFailureFallback(
				SegmentType.JINGLE,
				"フォールバックジングル",
				"asset-fallback",
				"/api/assets/audio/asset-fallback.wav",
				"JINGLE_FALLBACK"));

		radioService.handleAsyncGenerationFailure("playout-001", "queue-001", "PROVIDER_TIMEOUT");

		QueueItemEntity updated = queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001").stream()
				.filter(item -> "queue-001".equals(item.getId()))
				.findFirst()
				.orElseThrow();
		assertEquals(QueueItemStatus.PLAYING, updated.getStatus());
		assertEquals(SegmentType.JINGLE, updated.getSegmentType());
		assertEquals("asset-fallback", updated.getAssetId());
		assertEquals("/api/assets/audio/asset-fallback.wav", updated.getAssetUrl());
		assertEquals("JINGLE_FALLBACK", updated.getContentOrigin());
		assertEquals("queue-001", session.getCurrentQueueItemId());
		assertEquals(ProgramBlockSlotStatus.QUEUED, musicSlot.getStatus());
	}

	@Test
	void handleAsyncGenerationFailureReleasesMusicAheadCapacityForNextPlannedMusic() {
		when(settingsStore.load()).thenReturn(settingsDocument(new SettingsDocument.PlayoutSettings(3, 2, 90_000, 480_000, 2, 4, 3, 1, true)));
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-001");
		session.setCurrentProgramBlockId("block-current");
		ProgramBlockEntity currentBlock = programBlock("block-current", "playout-001", ProgramBlockStatus.ACTIVE);
		ProgramBlockSlotEntity currentSlot = blockSlot("slot-1", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.TOPIC, 30_000);
		ProgramBlockSlotEntity failedSlot = blockSlot("slot-2", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.MUSIC_BREAK, 45_000);
		failedSlot.setResolvedSegmentType(SegmentType.MUSIC_AI);
		ProgramBlockSlotEntity nextMusicSlot = blockSlot("slot-3", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.MUSIC_BREAK, 45_000);
		nextMusicSlot.setResolvedSegmentType(SegmentType.MUSIC_AI);
		QueueItemEntity current = queueItem("queue-001", "playout-001", QueueItemStatus.PLAYING);
		current.setProgramBlockId("block-current");
		current.setProgramSlotId("slot-1");
		current.setAssetId("asset-current");
		QueueItemEntity failedMusic = queueItem("queue-002", "playout-001", QueueItemStatus.GENERATING);
		failedMusic.setSequenceNo(2);
		failedMusic.setProgramBlockId("block-current");
		failedMusic.setProgramSlotId("slot-2");
		failedMusic.setSegmentType(SegmentType.MUSIC_AI);
		failedMusic.setSlotRole(SlotRole.MUSIC_BREAK);
		failedMusic.setTitle("失敗したAIミュージック");
		failedMusic.setAssetId(null);
		failedMusic.setAssetUrl(null);
		QueueItemEntity plannedMusic = queueItem("queue-003", "playout-001", QueueItemStatus.PLANNED);
		plannedMusic.setSequenceNo(3);
		plannedMusic.setProgramBlockId("block-current");
		plannedMusic.setProgramSlotId("slot-3");
		plannedMusic.setSegmentType(SegmentType.MUSIC_AI);
		plannedMusic.setSlotRole(SlotRole.MUSIC_BREAK);
		plannedMusic.setTitle("次のAIミュージック");
		plannedMusic.setAssetId(null);
		plannedMusic.setAssetUrl(null);
		List<QueueItemEntity> queueItems = new ArrayList<>(List.of(current, failedMusic, plannedMusic));

		wireRepositoryState(
				session,
				List.of(currentBlock),
				Map.of("block-current", new ArrayList<>(List.of(currentSlot, failedSlot, nextMusicSlot))),
				queueItems);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));
		when(assetService.prepareMusicFailureFallback(failedMusic, "PROVIDER_TIMEOUT")).thenReturn(new AssetService.MusicFailureFallback(
				SegmentType.JINGLE,
				"フォールバックジングル",
				"asset-fallback",
				"/api/assets/audio/asset-fallback.wav",
				"JINGLE_FALLBACK"));

		radioService.handleAsyncGenerationFailure("playout-001", "queue-002", "PROVIDER_TIMEOUT");

		List<QueueItemEntity> updatedItems = queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001");
		assertTrue(updatedItems.stream().anyMatch(item -> "queue-002".equals(item.getId())
				&& item.getSegmentType() == SegmentType.JINGLE
				&& item.getStatus() == QueueItemStatus.READY));
		assertTrue(updatedItems.stream().anyMatch(item -> "queue-003".equals(item.getId())
				&& item.getStatus() == QueueItemStatus.GENERATING));
		verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof GenerateMusicRequested requested
				&& "queue-003".equals(requested.queueItemId())));
	}

	@Test
	void recordPlaybackEventRefillsQueueWhenBufferDropsBelowThreshold() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-002");
		session.setCurrentProgramBlockId("block-current");

		ProgramBlockEntity currentBlock = programBlock("block-current", "playout-001", ProgramBlockStatus.ACTIVE);
		List<ProgramBlockSlotEntity> currentSlots = new ArrayList<>(List.of(
				blockSlot("slot-1", "block-current", ProgramBlockSlotStatus.DONE, SlotRole.OPENING, 30_000),
				blockSlot("slot-2", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.TOPIC, 30_000),
				blockSlot("slot-3", "block-current", ProgramBlockSlotStatus.PLANNED, SlotRole.TOPIC, 30_000),
				blockSlot("slot-4", "block-current", ProgramBlockSlotStatus.PLANNED, SlotRole.ENDING, 30_000)));

		QueueItemEntity done = queueItem("queue-001", "playout-001", QueueItemStatus.DONE);
		done.setProgramBlockId("block-current");
		done.setProgramSlotId("slot-1");
		QueueItemEntity current = queueItem("queue-002", "playout-001", QueueItemStatus.PLAYING);
		current.setProgramBlockId("block-current");
		current.setProgramSlotId("slot-2");
		List<QueueItemEntity> queueItems = new ArrayList<>(List.of(done, current));

		wireRepositoryState(session, List.of(currentBlock), Map.of("block-current", currentSlots), queueItems);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));

		radioService.recordPlaybackEvent(new PlaybackEventRequest(
				"web-client",
				"playout-001",
				"queue-002",
				PlaybackEventType.SEGMENT_ENDED,
				Instant.now()));

		List<QueueItemEntity> updatedItems = queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001");
		assertEquals(4, updatedItems.size());
		assertEquals(2L, updatedItems.stream().filter(item -> item.getStatus() == QueueItemStatus.READY).count());
		assertEquals(2, session.getBufferReadyCount());
		assertTrue(updatedItems.stream().anyMatch(item -> "slot-3".equals(item.getProgramSlotId()) && item.getStatus() == QueueItemStatus.READY));
		assertTrue(updatedItems.stream().anyMatch(item -> "slot-4".equals(item.getProgramSlotId()) && item.getStatus() == QueueItemStatus.READY));
	}

	@Test
	void refillUsesArchiveReplayForSoftSlotWhenCandidateExists() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-002");
		session.setCurrentProgramBlockId("block-current");

		ProgramBlockEntity currentBlock = programBlock("block-current", "playout-001", ProgramBlockStatus.ACTIVE);
		List<ProgramBlockSlotEntity> currentSlots = new ArrayList<>(List.of(
				blockSlot("slot-1", "block-current", ProgramBlockSlotStatus.DONE, SlotRole.OPENING, 30_000),
				blockSlot("slot-2", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.TOPIC, 30_000),
				blockSlot("slot-3", "block-current", ProgramBlockSlotStatus.PLANNED, SlotRole.TOPIC, 30_000),
				blockSlot("slot-4", "block-current", ProgramBlockSlotStatus.PLANNED, SlotRole.ENDING, 30_000)));

		QueueItemEntity done = queueItem("queue-001", "playout-001", QueueItemStatus.DONE);
		done.setProgramBlockId("block-current");
		done.setProgramSlotId("slot-1");
		QueueItemEntity current = queueItem("queue-002", "playout-001", QueueItemStatus.PLAYING);
		current.setProgramBlockId("block-current");
		current.setProgramSlotId("slot-2");
		List<QueueItemEntity> queueItems = new ArrayList<>(List.of(done, current));

		BroadcastArchiveEntity archive = new BroadcastArchiveEntity();
		archive.setId("archive-001");
		archive.setStationId("station-night");
		archive.setSourcePlayHistoryId("play-history-source");
		archive.setSegmentType(SegmentType.TALK);
		archive.setTitle("再放送トーク");
		archive.setPrimaryAssetId("asset-archive");
		when(broadcastArchiveService.findReplayCandidate("station-night", SegmentType.TALK, "block-current", 4)).thenReturn(Optional.of(archive));
		when(programmingService.resolveCurrentPlan(anyString(), any(OffsetDateTime.class))).thenReturn(new ProgrammingService.ResolvedProgramPlan(
				"tmpl-next",
				4,
				"次の番組",
				60_000,
				List.of(new ProgrammingService.ResolvedSlot("next-slot-1", SlotRole.ENDING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.JINGLE)),
				false,
				List.of()));

		wireRepositoryState(session, List.of(currentBlock), Map.of("block-current", currentSlots), queueItems);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));

		radioService.recordPlaybackEvent(new PlaybackEventRequest(
				"web-client",
				"playout-001",
				"queue-002",
				PlaybackEventType.SEGMENT_ENDED,
				Instant.now()));

		QueueItemEntity replay = queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001").stream()
				.filter(item -> "slot-3".equals(item.getProgramSlotId()))
				.findFirst()
				.orElseThrow();
		assertEquals("ARCHIVE_REPLAY", replay.getContentOrigin());
		assertEquals("play-history-source", replay.getReplayOfPlayHistoryId());
		assertEquals("asset-archive", replay.getAssetId());
		assertEquals("/api/assets/audio/asset-archive.wav", replay.getAssetUrl());
		assertEquals(QueueItemStatus.READY, replay.getStatus());
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
	void recordPlaybackEventDoesNotPlanNextProgramBlockInRealTimeOnlyMode() {
		StationProgrammingPolicyEntity realTimeOnlyPolicy = programmingPolicy("station-night", "REALTIME_ONLY", 1, 1);
		when(programmingPolicyRepository.findByStationId("station-night")).thenReturn(Optional.of(realTimeOnlyPolicy));
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-002");
		session.setCurrentProgramBlockId("block-current");

		ProgramBlockEntity currentBlock = programBlock("block-current", "playout-001", ProgramBlockStatus.ACTIVE);
		List<ProgramBlockSlotEntity> currentSlots = new ArrayList<>(List.of(
				blockSlot("slot-1", "block-current", ProgramBlockSlotStatus.DONE, SlotRole.OPENING, 30_000),
				blockSlot("slot-2", "block-current", ProgramBlockSlotStatus.QUEUED, SlotRole.TOPIC, 60_000),
				blockSlot("slot-3", "block-current", ProgramBlockSlotStatus.PLANNED, SlotRole.ENDING, 30_000)));

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

		radioService.recordPlaybackEvent(new PlaybackEventRequest(
				"web-client",
				"playout-001",
				"queue-002",
				PlaybackEventType.SEGMENT_ENDED,
				Instant.now()));

		assertTrue(queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001").stream()
				.map(QueueItemEntity::getProgramBlockId)
				.filter(programBlockId -> programBlockId != null)
				.allMatch("block-current"::equals));
		verify(programmingService, never()).resolveCurrentPlan(anyString(), any(OffsetDateTime.class));
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

	private ScriptDirectiveSnapshot scriptSnapshot() {
		return new ScriptDirectiveSnapshot(
				"台本",
				"台本",
				List.of(),
				"calm",
				"medium",
				List.of(),
				"persona-night-main",
				"voice-night-main",
				List.of());
	}

	@Test
	void recordPlaybackEventKeepsLiveGenerationWhenReplayQuotaIsExceeded() {
		PlayoutSessionEntity session = session("playout-001", PlayoutState.PLAYING, "queue-002");
		session.setCurrentProgramBlockId("block-current");
		ProgramBlockEntity currentBlock = programBlock("block-current", "playout-001", ProgramBlockStatus.ACTIVE);
		List<ProgramBlockSlotEntity> currentSlots = new ArrayList<>(List.of(
				blockSlot("slot-1", "block-current", ProgramBlockSlotStatus.DONE, SlotRole.OPENING, 30_000),
				blockSlot("slot-2", "block-current", ProgramBlockSlotStatus.DONE, SlotRole.TOPIC, 30_000),
				blockSlot("slot-3", "block-current", ProgramBlockSlotStatus.PLANNED, SlotRole.TOPIC, 30_000),
				blockSlot("slot-4", "block-current", ProgramBlockSlotStatus.PLANNED, SlotRole.ENDING, 30_000)));
		QueueItemEntity doneReplay = queueItem("queue-001", "playout-001", QueueItemStatus.DONE);
		doneReplay.setProgramBlockId("block-current");
		doneReplay.setProgramSlotId("slot-1");
		doneReplay.setContentOrigin("ARCHIVE_REPLAY");
		doneReplay.setReplayOfPlayHistoryId("play-history-source");
		QueueItemEntity current = queueItem("queue-002", "playout-001", QueueItemStatus.PLAYING);
		current.setProgramBlockId("block-current");
		current.setProgramSlotId("slot-2");
		List<QueueItemEntity> queueItems = new ArrayList<>(List.of(doneReplay, current));

		when(broadcastArchiveService.findReplayCandidate("station-night", SegmentType.TALK, "block-current", 4)).thenReturn(Optional.empty());
		when(programmingService.resolveCurrentPlan(anyString(), any(OffsetDateTime.class))).thenReturn(new ProgrammingService.ResolvedProgramPlan(
				"tmpl-next",
				4,
				"次の番組",
				60_000,
				List.of(new ProgrammingService.ResolvedSlot("next-slot-1", SlotRole.ENDING, com.seedshiftradio.domain.ConstraintMode.HARD, 30_000, SegmentType.JINGLE)),
				false,
				List.of()));

		wireRepositoryState(session, List.of(currentBlock), Map.of("block-current", currentSlots), queueItems);
		when(playoutSessionRepository.findById("playout-001")).thenReturn(Optional.of(session));

		radioService.recordPlaybackEvent(new PlaybackEventRequest(
				"web-client",
				"playout-001",
				"queue-002",
				PlaybackEventType.SEGMENT_ENDED,
				Instant.now()));

		QueueItemEntity next = queueItemRepository.findBySessionIdOrderBySequenceNoAsc("playout-001").stream()
				.filter(item -> "slot-3".equals(item.getProgramSlotId()))
				.findFirst()
				.orElseThrow();
		assertEquals("LIVE_GEN", next.getContentOrigin());
		assertNull(next.getReplayOfPlayHistoryId());
		assertEquals(QueueItemStatus.READY, next.getStatus());
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
		when(queueItemRepository.findTopBySessionIdAndStatusOrderBySequenceNoAsc(session.getId(), QueueItemStatus.READY)).thenAnswer(invocation -> sessionQueueItems.stream()
				.filter(item -> item.getStatus() == QueueItemStatus.READY)
				.sorted(Comparator.comparing(QueueItemEntity::getSequenceNo))
				.findFirst());
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
		when(queueItemRepository.countByProgramBlockIdAndContentOrigin(anyString(), anyString())).thenAnswer(invocation -> sessionQueueItems.stream()
				.filter(item -> invocation.getArgument(0).equals(item.getProgramBlockId()))
				.filter(item -> invocation.getArgument(1).equals(item.getContentOrigin()))
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

	private StationEntity station() {
		return new StationEntity(
				"station-night",
				"Midnight Echo",
				new java.math.BigDecimal("81.3"),
				"talk",
				"persona-night-main",
				"voice-night-main",
				true,
				"tmpl-night-regular",
				true);
	}

	private StationProgrammingPolicyEntity programmingPolicy(
			String stationId,
			String mode,
			int maxPreparedMinutes,
			int maxPreparedBlocks) {
		StationProgrammingPolicyEntity policy = org.mockito.Mockito.mock(StationProgrammingPolicyEntity.class);
		org.mockito.Mockito.doReturn(ProgrammingPolicyProfileSupport.toMap(
				new ProgrammingPolicyProfileSupport.PreGenerationProfile(
						mode,
						maxPreparedMinutes,
						maxPreparedBlocks,
						true))).when(policy).getPreGenerationPolicy();
		return policy;
	}

	private SettingsDocument settingsDocument(SettingsDocument.PlayoutSettings playout) {
		SettingsDocument defaults = SettingsDocument.defaults();
		return new SettingsDocument(
				defaults.version(),
				defaults.schemaVersion(),
				defaults.updatedAt(),
				defaults.server(),
				defaults.paths(),
				playout,
				defaults.cache(),
				defaults.programming(),
				defaults.providers(),
				defaults.security(),
				defaults.features()).normalize();
	}
}
