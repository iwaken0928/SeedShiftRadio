package com.seedshiftradio.settings;

import com.seedshiftradio.domain.ProviderErrorCode;

public class ProviderRuntimeException extends RuntimeException {

	private final ProviderErrorCode providerErrorCode;

	public ProviderRuntimeException(ProviderErrorCode providerErrorCode, String message) {
		super(message);
		this.providerErrorCode = normalize(providerErrorCode);
	}

	public ProviderRuntimeException(ProviderErrorCode providerErrorCode, String message, Throwable cause) {
		super(message, cause);
		this.providerErrorCode = normalize(providerErrorCode);
	}

	public ProviderRuntimeException(String externalErrorCode, String message) {
		this(ProviderErrorClassifier.normalizeExternalCode(externalErrorCode), message);
	}

	public ProviderRuntimeException(String externalErrorCode, String message, Throwable cause) {
		this(ProviderErrorClassifier.normalizeExternalCode(externalErrorCode), message, cause);
	}

	public ProviderErrorCode providerErrorCode() {
		return providerErrorCode;
	}

	public String errorCode() {
		return providerErrorCode.name();
	}

	private static ProviderErrorCode normalize(ProviderErrorCode providerErrorCode) {
		return providerErrorCode == null ? ProviderErrorCode.PROVIDER_BAD_RESPONSE : providerErrorCode;
	}
}
