package com.seedshiftradio.radio;

import com.seedshiftradio.domain.PlayoutState;

public record TuneResponse(
		String sessionId,
		String stationId,
		PlayoutState state,
		boolean queueWarmupStarted,
		String correlationId) {
}
