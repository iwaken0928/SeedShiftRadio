package com.seedshiftradio.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seedshift.radio.security")
public record AdminTokenProperties(String adminToken) {

	public boolean isConfigured() {
		return adminToken != null && !adminToken.isBlank();
	}
}
