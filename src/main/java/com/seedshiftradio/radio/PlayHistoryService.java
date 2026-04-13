package com.seedshiftradio.radio;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.domain.PlayHistoryResultStatus;

@Service
public class PlayHistoryService {

	private final PlayHistoryRepository playHistoryRepository;
	private final BroadcastArchiveService broadcastArchiveService;

	public PlayHistoryService(PlayHistoryRepository playHistoryRepository, BroadcastArchiveService broadcastArchiveService) {
		this.playHistoryRepository = playHistoryRepository;
		this.broadcastArchiveService = broadcastArchiveService;
	}

	@Transactional
	public void record(PlayoutSessionEntity session, QueueItemEntity item, PlayHistoryResultStatus resultStatus) {
		PlayHistoryEntity entity = new PlayHistoryEntity();
		entity.setId(nextId());
		entity.setSessionId(session.getId());
		entity.setStationId(session.getStationId());
		entity.setQueueItemId(item.getId());
		entity.setLetterId(item.getLetterId());
		entity.setProgramBlockId(item.getProgramBlockId());
		entity.setProgramSlotId(item.getProgramSlotId());
		entity.setSegmentType(item.getSegmentType());
		entity.setTitle(item.getTitle());
		entity.setPlaybackMode(item.getPlaybackMode());
		entity.setResultStatus(resultStatus);
		entity.setCorrelationId(item.getCorrelationId());
		entity.setContentOrigin(item.getContentOrigin() == null || item.getContentOrigin().isBlank() ? "LIVE_GEN" : item.getContentOrigin());
		entity.setReplayOfPlayHistoryId(item.getReplayOfPlayHistoryId());
		entity.setPlayedAt(Instant.now());
		PlayHistoryEntity saved = playHistoryRepository.save(entity);
		if (resultStatus == PlayHistoryResultStatus.DONE) {
			broadcastArchiveService.markReplayed(item.getReplayOfPlayHistoryId());
			broadcastArchiveService.promoteIfEligible(saved, item);
		}
	}

	private String nextId() {
		return "play-history-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
