package com.seedshiftradio.radio;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.PlayHistoryResultStatus;
import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;

@ExtendWith(MockitoExtension.class)
class PromoteArchiveCandidateJobTests {

	@Mock
	PlayHistoryRepository playHistoryRepository;

	@Mock
	QueueItemRepository queueItemRepository;

	@Mock
	BroadcastArchiveService broadcastArchiveService;

	PromoteArchiveCandidateJob job;

	@BeforeEach
	void setUp() {
		job = new PromoteArchiveCandidateJob(
				playHistoryRepository,
				queueItemRepository,
				broadcastArchiveService);
	}

	@Test
	void runReloadsHistoryAndQueueItemThenMarksReplayAndPromotes() {
		PlayHistoryEntity history = playHistory(PlayHistoryResultStatus.DONE);
		QueueItemEntity item = queueItem();
		when(playHistoryRepository.findById("play-history-001")).thenReturn(Optional.of(history));
		when(queueItemRepository.findById("queue-001")).thenReturn(Optional.of(item));

		job.run("play-history-001", "queue-001", "play-history-source");

		verify(broadcastArchiveService).markReplayed("play-history-source");
		verify(broadcastArchiveService).promoteIfEligible(history, item);
	}

	@Test
	void runUsesReloadedHistoryReplaySourceWhenEventValueIsBlank() {
		PlayHistoryEntity history = playHistory(PlayHistoryResultStatus.DONE);
		history.setReplayOfPlayHistoryId("play-history-from-db");
		QueueItemEntity item = queueItem();
		when(playHistoryRepository.findById("play-history-001")).thenReturn(Optional.of(history));
		when(queueItemRepository.findById("queue-001")).thenReturn(Optional.of(item));

		job.run("play-history-001", "queue-001", " ");

		verify(broadcastArchiveService).markReplayed("play-history-from-db");
		verify(broadcastArchiveService).promoteIfEligible(history, item);
	}

	@Test
	void runSkipsWhenReloadedHistoryIsNotDone() {
		when(playHistoryRepository.findById("play-history-001"))
				.thenReturn(Optional.of(playHistory(PlayHistoryResultStatus.STOPPED)));

		job.run("play-history-001", "queue-001", "play-history-source");

		verifyNoInteractions(queueItemRepository, broadcastArchiveService);
	}

	private PlayHistoryEntity playHistory(PlayHistoryResultStatus status) {
		PlayHistoryEntity history = new PlayHistoryEntity();
		history.setId("play-history-001");
		history.setSessionId("playout-001");
		history.setStationId("station-night");
		history.setQueueItemId("queue-001");
		history.setProgramBlockId("program-001");
		history.setProgramSlotId("slot-001");
		history.setSegmentType(SegmentType.MUSIC_AI);
		history.setTitle("放送済みセグメント");
		history.setPlaybackMode(PlaybackMode.SERVER_AUDIO);
		history.setResultStatus(status);
		history.setCorrelationId("corr-001");
		history.setContentOrigin("LIVE_GEN");
		return history;
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
		item.setAssetId("asset-001");
		item.setDurationMs(30_000);
		item.setCorrelationId("corr-001");
		item.setContentOrigin("LIVE_GEN");
		return item;
	}
}
