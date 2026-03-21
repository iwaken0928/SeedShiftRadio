package com.seedshiftradio.radio;

import java.time.Instant;

public record HealthResponse(
		String status,
		Instant checkedAt,
		long stationCount,
		long sessionCount,
		long queueCount,
		String latestEventId,
		String currentSessionId) {
}
