package com.seedshiftradio.settings;

import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.domain.ProviderType;

public final class ProviderErrorClassifier {

	private ProviderErrorClassifier() {
	}

	public static ProviderErrorCode fromHttpStatus(int statusCode) {
		if (statusCode == 401 || statusCode == 403) {
			return ProviderErrorCode.PROVIDER_AUTH_FAILED;
		}
		if (statusCode == 408 || statusCode == 504) {
			return ProviderErrorCode.PROVIDER_TIMEOUT;
		}
		if (statusCode == 429 || statusCode == 503) {
			return ProviderErrorCode.PROVIDER_RESOURCE_EXHAUSTED;
		}
		if (statusCode >= 400 && statusCode < 500) {
			return ProviderErrorCode.PROVIDER_REJECTED;
		}
		return ProviderErrorCode.PROVIDER_BAD_RESPONSE;
	}

	public static ProviderErrorCode normalizeExternalCode(String externalCode) {
		return ProviderErrorCode.normalize(externalCode);
	}

	public static boolean fallbackAllowed(ProviderType providerType, ProviderErrorCode errorCode) {
		if (providerType == null || errorCode == null) {
			return false;
		}
		return switch (errorCode) {
			case PROVIDER_UNREACHABLE, PROVIDER_TIMEOUT, PROVIDER_BAD_RESPONSE, PROVIDER_RESOURCE_EXHAUSTED -> true;
			case VOICE_REF_NOT_FOUND, VOICE_CONSENT_REQUIRED -> providerType == ProviderType.TTS;
			case PROVIDER_REJECTED, PROVIDER_AUTH_FAILED, PROVIDER_INTERRUPTED -> false;
		};
	}
}
