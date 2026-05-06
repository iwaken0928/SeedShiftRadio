package com.seedshiftradio.settings;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "seedshift.radio.cache.eviction", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GeneratedAssetEvictionJob {

	private final GeneratedAssetService generatedAssetService;

	public GeneratedAssetEvictionJob(GeneratedAssetService generatedAssetService) {
		this.generatedAssetService = generatedAssetService;
	}

	@Scheduled(cron = "${seedshift.radio.cache.eviction.cron:0 17 * * * *}")
	public void runScheduled() {
		runOnce();
	}

	public GeneratedAssetService.CacheEvictionResult runOnce() {
		return generatedAssetService.evictCache();
	}
}
