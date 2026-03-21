package com.seedshiftradio.radio;

import com.seedshiftradio.domain.PlayoutState;

public record RadioControlResponse(
		String sessionId,
		String stationId,
		PlayoutState state,
		String correlationId) {
}
