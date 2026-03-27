package com.seedshiftradio.monitor;

import java.time.Instant;
import java.util.Map;

import com.seedshiftradio.domain.PlayoutState;
import com.seedshiftradio.settings.SettingsDtos;

public final class MonitorDtos {

	private MonitorDtos() {
	}

	public record MonitorSummaryResponse(
			String sessionId,
			String stationId,
			PlayoutState state,
			Integer bufferReadyCount,
			long pendingLetterCount,
			boolean degraded,
			Map<String, SettingsDtos.ProviderHealthPayload> providerHealth,
			Instant updatedAt) {
	}
}
