package com.seedshiftradio.radio;

import java.time.Instant;

import com.seedshiftradio.domain.PlayoutState;

public record RadioStatusResponse(
		String sessionId,
		String stationId,
		String programBlockId,
		String programTemplateId,
		String programTitle,
		PlayoutState state,
		String currentItemId,
		Integer bufferReadyCount,
		boolean degraded,
		Instant updatedAt,
		String correlationId) {

	public static RadioStatusResponse idle() {
		return new RadioStatusResponse(null, null, null, null, null, PlayoutState.IDLE, null, 0, false, Instant.now(), null);
	}
}
