package com.seedshiftradio.radio;

import java.time.Instant;

public record MonitorSummaryResponse(
		String correlationId,
		Instant checkedAt,
		RadioStatusResponse currentStatus,
		long readyQueueCount,
		long pendingLetterCount,
		int clientCapabilityCount,
		String latestEventId) {
}
