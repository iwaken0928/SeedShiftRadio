package com.seedshiftradio.radio;

import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.domain.QueueItemStatus;

public record QueueItemResponse(
		String id,
		String programBlockId,
		String programSlotId,
		String slotRole,
		String type,
		String title,
		PlaybackMode playbackMode,
		String assetUrl,
		String speechDirectiveId,
		Integer durationMs,
		QueueItemStatus status,
		String correlationId,
		boolean assetBanned) {
}
