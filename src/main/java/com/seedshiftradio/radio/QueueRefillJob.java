package com.seedshiftradio.radio;

import org.springframework.stereotype.Component;

@Component
public class QueueRefillJob {

	private final RadioService radioService;

	public QueueRefillJob(RadioService radioService) {
		this.radioService = radioService;
	}

	public void run(String sessionId) {
		radioService.refillQueue(sessionId);
	}
}
