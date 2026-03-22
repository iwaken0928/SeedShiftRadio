package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.PlayoutState;
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
}
