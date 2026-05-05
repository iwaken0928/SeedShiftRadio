package com.seedshiftradio.radio;

import java.time.Instant;
import java.util.Map;

import com.seedshiftradio.settings.SettingsDtos;

public record HealthResponse(
		String status,
		Instant checkedAt,
		long stationCount,
		long sessionCount,
		long queueCount,
		String latestEventId,
		String currentSessionId,
		Map<String, SettingsDtos.ProviderHealthPayload> providerHealth) {
}
