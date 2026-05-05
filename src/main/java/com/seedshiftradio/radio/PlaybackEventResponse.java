package com.seedshiftradio.radio;

import com.seedshiftradio.domain.PlayoutState;

public record PlaybackEventResponse(
		String correlationId,
		PlayoutState state,
		Integer bufferReadyCount) {
}
