package com.seedshiftradio.monitor;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.seedshiftradio.radio.HealthResponse;
import com.seedshiftradio.radio.RadioService;
import com.seedshiftradio.settings.ProviderHealthService;
import com.seedshiftradio.settings.SettingsDtos;

@Service
public class HealthService {

	private final RadioService radioService;
	private final ProviderHealthService providerHealthService;

	public HealthService(RadioService radioService, ProviderHealthService providerHealthService) {
		this.radioService = radioService;
		this.providerHealthService = providerHealthService;
	}

	public HealthResponse health() {
		HealthResponse base = radioService.health();
		Map<String, SettingsDtos.ProviderHealthPayload> providerHealth = providerHealthService.getLatestOrProbe();
		return new HealthResponse(
				aggregateStatus(providerHealth),
				base.checkedAt(),
				base.stationCount(),
				base.sessionCount(),
				base.queueCount(),
				base.latestEventId(),
				base.currentSessionId(),
				providerHealth);
	}

	private String aggregateStatus(Map<String, SettingsDtos.ProviderHealthPayload> providerHealth) {
		boolean hasDown = providerHealth.values().stream().anyMatch(health -> "DOWN".equals(health.status()));
		if (hasDown) {
			return "DEGRADED";
		}
		boolean hasDegraded = providerHealth.values().stream().anyMatch(health -> "DEGRADED".equals(health.status()));
		return hasDegraded ? "DEGRADED" : "UP";
	}
}
