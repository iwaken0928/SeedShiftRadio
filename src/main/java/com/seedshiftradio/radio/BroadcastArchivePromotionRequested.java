package com.seedshiftradio.radio;

public record BroadcastArchivePromotionRequested(
		String playHistoryId,
		String queueItemId,
		String replayOfPlayHistoryId) {
}
