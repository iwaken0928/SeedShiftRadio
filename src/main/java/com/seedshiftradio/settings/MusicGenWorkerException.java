package com.seedshiftradio.settings;

import com.seedshiftradio.domain.ProviderErrorCode;

public class MusicGenWorkerException extends ProviderRuntimeException {

	public MusicGenWorkerException(String errorCode, String message) {
		super(errorCode, message);
	}

	public MusicGenWorkerException(String errorCode, String message, Throwable cause) {
		super(errorCode, message, cause);
	}

	public MusicGenWorkerException(ProviderErrorCode errorCode, String message) {
		super(errorCode, message);
	}

	public MusicGenWorkerException(ProviderErrorCode errorCode, String message, Throwable cause) {
		super(errorCode, message, cause);
	}
}
