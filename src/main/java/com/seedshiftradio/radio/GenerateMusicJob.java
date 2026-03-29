package com.seedshiftradio.radio;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.settings.AssetService;

@Component
public class GenerateMusicJob {

	private final QueueItemRepository queueItemRepository;
	private final AssetService assetService;
	private final RadioService radioService;

	public GenerateMusicJob(
			QueueItemRepository queueItemRepository,
			AssetService assetService,
			RadioService radioService) {
		this.queueItemRepository = queueItemRepository;
		this.assetService = assetService;
		this.radioService = radioService;
	}

	@Transactional
	public void run(String queueItemId, String correlationId) {
		QueueItemEntity item = queueItemRepository.findById(queueItemId).orElse(null);
		if (item == null || item.getStatus() != QueueItemStatus.GENERATING) {
			return;
		}

		try {
			assetService.ensureQueueAudioAsset(item);
			item.setStatus(QueueItemStatus.READY);
			queueItemRepository.save(item);
			radioService.synchronizeSessionAfterAsyncUpdate(item.getSessionId());
		} catch (RuntimeException exception) {
			item.setStatus(QueueItemStatus.FAILED);
			item.setAssetBanned(true);
			queueItemRepository.save(item);
			radioService.synchronizeSessionAfterAsyncUpdate(item.getSessionId());
			throw exception;
		}
	}
}
