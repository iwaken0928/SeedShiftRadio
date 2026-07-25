package com.seedshiftradio.management;

import org.springframework.stereotype.Component;

@Component
public class PreGenerationJob {

	private final ManagementService managementService;

	public PreGenerationJob(ManagementService managementService) {
		this.managementService = managementService;
	}

	public void run(String requestId) {
		try {
			managementService.runPreGeneration(requestId);
		} catch (RuntimeException exception) {
			managementService.markPreGenerationFailed(requestId);
		}
	}
}
