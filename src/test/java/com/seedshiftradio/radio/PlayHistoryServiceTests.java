package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.seedshiftradio.domain.PlayHistoryResultStatus;
import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;

@ExtendWith(MockitoExtension.class)
class PlayHistoryServiceTests {

	@Mock
	PlayHistoryRepository playHistoryRepository;

	@Mock
	ApplicationEventPublisher eventPublisher;

	PlayHistoryService service;

	@BeforeEach
	void setUp() {
		service = new PlayHistoryService(playHistoryRepository, eventPublisher);
		when(playHistoryRepository.save(any(PlayHistoryEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void recordPublishesArchivePromotionEventAfterDoneHistoryIsSaved() {
		PlayoutSessionEntity session = session();
		QueueItemEntity item = queueItem();
		item.setReplayOfPlayHistoryId("play-history-source");
		ArgumentCaptor<PlayHistoryEntity> historyCaptor = ArgumentCaptor.forClass(PlayHistoryEntity.class);
		ArgumentCaptor<BroadcastArchivePromotionRequested> eventCaptor = ArgumentCaptor.forClass(BroadcastArchivePromotionRequested.class);

		service.record(session, item, PlayHistoryResultStatus.DONE);

		verify(playHistoryRepository).save(historyCaptor.capture());
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		PlayHistoryEntity saved = historyCaptor.getValue();
		BroadcastArchivePromotionRequested event = eventCaptor.getValue();
		assertNotNull(saved.getId());
		assertEquals(saved.getId(), event.playHistoryId());
		assertEquals("queue-001", event.queueItemId());
		assertEquals("play-history-source", event.replayOfPlayHistoryId());
		assertEquals(PlayHistoryResultStatus.DONE, saved.getResultStatus());
	}

	@Test
	void recordDoesNotPublishArchivePromotionEventForNonDoneHistory() {
		service.record(session(), queueItem(), PlayHistoryResultStatus.STOPPED);

		verify(eventPublisher, never()).publishEvent(any());
	}

	private PlayoutSessionEntity session() {
		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setId("playout-001");
		session.setStationId("station-night");
		return session;
	}

	private QueueItemEntity queueItem() {
		QueueItemEntity item = new QueueItemEntity();
		item.setId("queue-001");
		item.setSessionId("playout-001");
		item.setSequenceNo(1);
		item.setSegmentType(SegmentType.MUSIC_AI);
		item.setStatus(QueueItemStatus.DONE);
		item.setProgramBlockId("program-001");
		item.setProgramSlotId("slot-001");
		item.setSlotRole(SlotRole.MUSIC_BREAK);
		item.setTitle("放送済みセグメント");
		item.setPlaybackMode(PlaybackMode.SERVER_AUDIO);
		item.setDurationMs(30_000);
		item.setCorrelationId("corr-001");
		item.setContentOrigin("LIVE_GEN");
		return item;
	}
}
