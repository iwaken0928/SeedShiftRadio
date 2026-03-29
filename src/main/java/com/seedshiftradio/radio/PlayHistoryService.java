package com.seedshiftradio.radio;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.domain.PlayHistoryResultStatus;

@Service
public class PlayHistoryService {

	private final PlayHistoryRepository playHistoryRepository;

	public PlayHistoryService(PlayHistoryRepository playHistoryRepository) {
		this.playHistoryRepository = playHistoryRepository;
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
		entity.setPlayedAt(Instant.now());
		playHistoryRepository.save(entity);
	}

	private String nextId() {
		return "play-history-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
