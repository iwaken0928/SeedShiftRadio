package com.seedshiftradio.common.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.seedshiftradio.common.correlation.CorrelationIdFilter;
import com.seedshiftradio.settings.ProviderRuntimeException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class ApiErrorHandler {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorResponse> handleApiException(ApiException exception, HttpServletRequest request) {
		return buildResponse(exception.getStatus(), exception.getCode(), exception.getMessage(), exception.getDetails(), request);
	}

	@ExceptionHandler({ MethodArgumentNotValidException.class, BindException.class })
	ResponseEntity<ErrorResponse> handleValidationException(Exception exception, HttpServletRequest request) {
		Map<String, Object> details = new LinkedHashMap<>();
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		if (exception instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
			for (FieldError fieldError : methodArgumentNotValidException.getBindingResult().getFieldErrors()) {
				fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
			}
		} else if (exception instanceof BindException bindException) {
			for (FieldError fieldError : bindException.getBindingResult().getFieldErrors()) {
				fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
			}
		}
		details.put("fieldErrors", fieldErrors);
		return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "入力値を確認してください。", details, request);
	}

	@ExceptionHandler(ProviderRuntimeException.class)
	ResponseEntity<ErrorResponse> handleProviderRuntimeException(ProviderRuntimeException exception, HttpServletRequest request) {
		return buildResponse(
				HttpStatus.SERVICE_UNAVAILABLE,
				"PROVIDER_UNAVAILABLE",
				"Provider を利用できません。",
				Map.of("providerErrorCode", exception.errorCode()),
				request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception, HttpServletRequest request) {
		return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "サーバー内部エラーが発生しました。", Map.of(), request);
	}

	private ResponseEntity<ErrorResponse> buildResponse(
			HttpStatus status,
			String code,
			String message,
			Map<String, Object> details,
			HttpServletRequest request) {
		ErrorResponse body = new ErrorResponse(
				Instant.now(),
				code,
				message,
				details == null ? Map.of() : details,
				CorrelationIdFilter.getCorrelationId(request));
		return ResponseEntity.status(status).body(body);
	}
}
