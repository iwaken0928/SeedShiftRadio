package com.seedshiftradio.radio;

import java.time.Instant;

public record RadioEventRecord(
		String id,
		String eventType,
		Instant occurredAt,
		Object payload) {
}
