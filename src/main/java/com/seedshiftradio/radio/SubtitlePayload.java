package com.seedshiftradio.radio;

import java.time.Instant;

public record SubtitlePayload(
		String sessionId,
		String itemId,
		String speechDirectiveId,
		String text,
		Instant updatedAt) {
}
