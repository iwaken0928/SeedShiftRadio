package com.seedshiftradio.radio;

import org.springframework.stereotype.Component;

@Component
public class WarmupQueueJob {

	private final RadioService radioService;

	public WarmupQueueJob(RadioService radioService) {
		this.radioService = radioService;
	}

	public void run(String sessionId) {
		radioService.warmupQueue(sessionId);
	}
}
