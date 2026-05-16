package com.seedshiftradio.settings;

public class TtsSynthesisException extends RuntimeException {

	private final String errorCode;

	public TtsSynthesisException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public TtsSynthesisException(String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return errorCode;
	}
}
