package com.seedshiftradio.settings;

import com.seedshiftradio.domain.ProviderErrorCode;

public class TtsSynthesisException extends ProviderRuntimeException {

	public TtsSynthesisException(String errorCode, String message) {
		super(errorCode, message);
	}

	public TtsSynthesisException(String errorCode, String message, Throwable cause) {
		super(errorCode, message, cause);
	}

	public TtsSynthesisException(ProviderErrorCode errorCode, String message) {
		super(errorCode, message);
	}

	public TtsSynthesisException(ProviderErrorCode errorCode, String message, Throwable cause) {
		super(errorCode, message, cause);
	}
}
