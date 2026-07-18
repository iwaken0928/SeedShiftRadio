package com.seedshiftradio.domain;

import java.util.Locale;

public enum ProviderErrorCode {

	PROVIDER_UNREACHABLE,
	PROVIDER_TIMEOUT,
	PROVIDER_BAD_RESPONSE,
	PROVIDER_REJECTED,
	PROVIDER_RESOURCE_EXHAUSTED,
	PROVIDER_AUTH_FAILED,
	PROVIDER_INTERRUPTED,
	VOICE_REF_NOT_FOUND,
	VOICE_CONSENT_REQUIRED;

	public static ProviderErrorCode normalize(String externalCode) {
		if (externalCode == null || externalCode.isBlank()) {
			return PROVIDER_BAD_RESPONSE;
		}
		try {
			return valueOf(externalCode.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return PROVIDER_BAD_RESPONSE;
		}
	}
}
