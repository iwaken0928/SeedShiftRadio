package com.seedshiftradio.monitor;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.seedshiftradio.common.security.AdminApiGuard;
import com.seedshiftradio.letter.LetterService;
import com.seedshiftradio.monitor.MonitorDtos.MonitorSummaryResponse;
import com.seedshiftradio.radio.RadioStatusResponse;
import com.seedshiftradio.radio.RadioService;

@Service
public class MonitorService {

	private final RadioService radioService;
	private final LetterService letterService;

	public MonitorService(RadioService radioService, LetterService letterService) {
		this.radioService = radioService;
		this.letterService = letterService;
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
				Map.of("musicGen", "UP", "tts", "UP", "llm", "UP"),
				status.updatedAt());
	}
}
