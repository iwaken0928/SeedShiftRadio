package com.seedshiftradio.settings;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seedshift.radio.config")
public record RadioConfigProperties(String path) {

	public String resolvedPath() {
		return (path == null || path.isBlank()) ? "./data/config/config.json" : path;
	}
}
