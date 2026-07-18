package com.seedshiftradio.common.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.seedshiftradio.common.correlation.CorrelationIdFilter;
import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.settings.TtsSynthesisException;

class ApiErrorHandlerTests {

	@Test
	void providerFailureReturnsSafeServiceUnavailableResponse() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(CorrelationIdFilter.HEADER_NAME, "corr-provider-001");
		TtsSynthesisException exception = new TtsSynthesisException(
				ProviderErrorCode.PROVIDER_REJECTED,
				"raw provider response with secret-token");

		ResponseEntity<ErrorResponse> response = new ApiErrorHandler().handleProviderRuntimeException(exception, request);

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
		ErrorResponse body = response.getBody();
		assertEquals("PROVIDER_UNAVAILABLE", body.code());
		assertEquals("Provider を利用できません。", body.message());
		assertEquals(Map.of("providerErrorCode", "PROVIDER_REJECTED"), body.details());
		assertEquals("corr-provider-001", body.correlationId());
		assertFalse(body.toString().contains("secret-token"));
	}
}
