package com.seedshiftradio.radio;

import java.time.Instant;

public record BufferWarningPayload(String sessionId, long readyCount, Instant occurredAt) {
}
