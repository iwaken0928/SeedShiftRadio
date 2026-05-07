package com.seedshiftradio.common.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CorrelationIdFilterTests {

	@Test
	void getCorrelationIdReturnsExistingRequestAttribute() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(CorrelationIdFilter.ATTRIBUTE_NAME, "corr-existing");

		String correlationId = CorrelationIdFilter.getCorrelationId(request);

		assertEquals("corr-existing", correlationId);
	}

	@Test
	void getCorrelationIdFallsBackToHeaderAndStoresAttribute() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(CorrelationIdFilter.HEADER_NAME, "corr-header");

		String correlationId = CorrelationIdFilter.getCorrelationId(request);

		assertEquals("corr-header", correlationId);
		assertEquals("corr-header", request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME));
	}

	@Test
	void getCorrelationIdGeneratesValueWhenRequestHasNone() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		String correlationId = CorrelationIdFilter.getCorrelationId(request);

		assertNotNull(correlationId);
		assertTrue(correlationId.startsWith("corr-"));
		assertEquals(correlationId, request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME));
	}
}
