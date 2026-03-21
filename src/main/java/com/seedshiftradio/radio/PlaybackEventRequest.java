package com.seedshiftradio.radio;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PlaybackEventRequest(
		@NotBlank String clientId,
		@NotBlank String sessionId,
		@NotBlank String itemId,
		@NotNull PlaybackEventType eventType,
		@NotNull Instant occurredAt) {
}
