package com.seedshiftradio.radio;

import org.springframework.stereotype.Component;

import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.settings.MusicGenWorkerException;
import com.seedshiftradio.settings.MusicGenerationRuntimeService;

@Component
public class GenerateMusicJob {

	private final QueueItemRepository queueItemRepository;
	private final PlayoutSessionRepository playoutSessionRepository;
	private final MusicGenerationRuntimeService musicGenerationRuntimeService;
	private final RadioService radioService;

	public GenerateMusicJob(
			QueueItemRepository queueItemRepository,
			PlayoutSessionRepository playoutSessionRepository,
			MusicGenerationRuntimeService musicGenerationRuntimeService,
			RadioService radioService) {
		this.queueItemRepository = queueItemRepository;
		this.playoutSessionRepository = playoutSessionRepository;
		this.musicGenerationRuntimeService = musicGenerationRuntimeService;
		this.radioService = radioService;
	}

	public void run(String queueItemId, String correlationId) {
		QueueItemEntity item = queueItemRepository.findById(queueItemId).orElse(null);
		if (item == null || item.getStatus() != QueueItemStatus.GENERATING) {
			return;
		}
		PlayoutSessionEntity session = playoutSessionRepository.findById(item.getSessionId()).orElse(null);
		if (session == null) {
			return;
		}

		try {
			MusicGenerationRuntimeService.GeneratedMusicAsset generatedAsset = musicGenerationRuntimeService.generate(session.getStationId(), item);
			QueueItemEntity latestItem = queueItemRepository.findById(queueItemId).orElse(item);
			if (latestItem.getStatus() != QueueItemStatus.GENERATING) {
				return;
			}
			latestItem.setAssetId(generatedAsset.assetId());
			latestItem.setAssetUrl(generatedAsset.assetUrl());
			latestItem.setContentOrigin(generatedAsset.contentOrigin());
			latestItem.setStatus(QueueItemStatus.READY);
			queueItemRepository.save(latestItem);
			radioService.synchronizeSessionAfterAsyncUpdate(latestItem.getSessionId());
		} catch (MusicGenWorkerException exception) {
			handleFailure(queueItemId, item.getSessionId(), exception.errorCode());
		} catch (RuntimeException exception) {
			handleFailure(queueItemId, item.getSessionId(), "PROVIDER_BAD_RESPONSE");
		}
	}

	private void handleFailure(String queueItemId, String sessionId, String errorCode) {
		QueueItemEntity latestItem = queueItemRepository.findById(queueItemId).orElse(null);
		if (latestItem == null) {
			return;
		}
		latestItem.setStatus(QueueItemStatus.FAILED);
		latestItem.setAssetBanned(true);
		queueItemRepository.save(latestItem);
		radioService.handleAsyncGenerationFailure(sessionId, queueItemId, errorCode);
	}
}
