package com.seedshiftradio.settings;

public class MusicGenWorkerException extends RuntimeException {

	private final String errorCode;

	public MusicGenWorkerException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public MusicGenWorkerException(String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return errorCode;
	}
}
