package com.seedshiftradio.radio;

import java.time.Instant;

import com.seedshiftradio.domain.PlayHistoryResultStatus;
import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.domain.SegmentType;

public final class PlayHistoryDtos {

	private PlayHistoryDtos() {
	}

	public record PlayHistoryResponse(
			String id,
			String sessionId,
			String stationId,
			String queueItemId,
			String programBlockId,
			String programSlotId,
			SegmentType segmentType,
			String title,
			PlaybackMode playbackMode,
			PlayHistoryResultStatus resultStatus,
			String correlationId,
			String contentOrigin,
			String replayOfPlayHistoryId,
			Instant playedAt,
			PlayHistoryLetterResponse letter) {
	}

	public record PlayHistoryLetterResponse(
			String letterId,
			String radioName,
			String subject,
			String adoptedInSessionId) {
	}
}
