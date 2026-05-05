package com.seedshiftradio.radio;

import org.springframework.stereotype.Component;

import com.seedshiftradio.domain.PlayHistoryResultStatus;

@Component
public class PromoteArchiveCandidateJob {

	private final PlayHistoryRepository playHistoryRepository;
	private final QueueItemRepository queueItemRepository;
	private final BroadcastArchiveService broadcastArchiveService;

	public PromoteArchiveCandidateJob(
			PlayHistoryRepository playHistoryRepository,
			QueueItemRepository queueItemRepository,
			BroadcastArchiveService broadcastArchiveService) {
		this.playHistoryRepository = playHistoryRepository;
		this.queueItemRepository = queueItemRepository;
		this.broadcastArchiveService = broadcastArchiveService;
	}

	public void run(String playHistoryId, String queueItemId, String replayOfPlayHistoryId) {
		PlayHistoryEntity history = playHistoryRepository.findById(playHistoryId).orElse(null);
		if (history == null || history.getResultStatus() != PlayHistoryResultStatus.DONE) {
			return;
		}

		String sourcePlayHistoryId = normalizeReplaySourceId(replayOfPlayHistoryId, history);
		broadcastArchiveService.markReplayed(sourcePlayHistoryId);

		QueueItemEntity item = queueItemRepository.findById(queueItemId).orElse(null);
		if (item == null) {
			return;
		}
		broadcastArchiveService.promoteIfEligible(history, item);
	}

	private String normalizeReplaySourceId(String replayOfPlayHistoryId, PlayHistoryEntity history) {
		if (replayOfPlayHistoryId != null && !replayOfPlayHistoryId.isBlank()) {
			return replayOfPlayHistoryId;
		}
		return history.getReplayOfPlayHistoryId();
	}
}
