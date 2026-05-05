package com.seedshiftradio.radio;

import java.util.List;

public record QueueSnapshotResponse(
		String sessionId,
		String stationId,
		List<QueueItemResponse> items,
		String correlationId) {
}
