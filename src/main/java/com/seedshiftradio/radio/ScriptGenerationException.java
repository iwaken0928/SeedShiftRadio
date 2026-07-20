package com.seedshiftradio.radio;

import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.settings.ProviderRuntimeException;

public class ScriptGenerationException extends ProviderRuntimeException {

	public ScriptGenerationException(ProviderErrorCode errorCode, String message) {
		super(errorCode, message);
	}

	public ScriptGenerationException(ProviderErrorCode errorCode, String message, Throwable cause) {
		super(errorCode, message, cause);
	}
}
