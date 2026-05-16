package com.seedshiftradio.common.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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

	@Test
	void filterStoresCorrelationAndSafePathIdsInMdcForRequestLogs() throws Exception {
		CorrelationIdFilter filter = new CorrelationIdFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/stations/station-001/programming");
		request.addHeader(CorrelationIdFilter.HEADER_NAME, "corr-header");
		request.addParameter("sessionId", "session-001");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			assertEquals("corr-header", MDC.get("correlationId"));
			assertEquals("station-001", MDC.get("stationId"));
			assertEquals("session-001", MDC.get("sessionId"));
		});

		assertEquals("corr-header", response.getHeader(CorrelationIdFilter.HEADER_NAME));
		assertNull(MDC.get("correlationId"));
		assertNull(MDC.get("stationId"));
		assertNull(MDC.get("sessionId"));
	}
}
