package com.seedshiftradio.radio;

import jakarta.validation.constraints.NotBlank;

public record TuneRequest(
		@NotBlank String stationId,
		String requestedBy,
		boolean resumePlayback) {
}
