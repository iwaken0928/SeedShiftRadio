package com.seedshiftradio.monitor;

import org.springframework.stereotype.Service;

import com.seedshiftradio.letter.LetterService;
import com.seedshiftradio.monitor.MonitorDtos.MonitorSummaryResponse;
import com.seedshiftradio.radio.RadioStatusResponse;
import com.seedshiftradio.radio.RadioService;
import com.seedshiftradio.settings.ProviderHealthService;

@Service
public class MonitorService {

	private final RadioService radioService;
	private final LetterService letterService;
	private final ProviderHealthService providerHealthService;

	public MonitorService(RadioService radioService, LetterService letterService, ProviderHealthService providerHealthService) {
		this.radioService = radioService;
		this.letterService = letterService;
		this.providerHealthService = providerHealthService;
	}

	public MonitorSummaryResponse summary() {
		RadioStatusResponse status = radioService.getStatus();
		long pendingLetters = status.stationId() == null ? 0 : letterService.countPendingLetters(status.stationId());
		return new MonitorSummaryResponse(
				status.sessionId(),
				status.stationId(),
				status.state(),
				status.bufferReadyCount(),
				pendingLetters,
				status.degraded(),
				providerHealthService.getLatestOrProbe(),
				status.updatedAt());
	}
}
