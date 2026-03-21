package com.seedshiftradio.common.correlation;

import java.io.IOException;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER_NAME = "X-Correlation-Id";
	public static final String ATTRIBUTE_NAME = "seedshiftRadioCorrelationId";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String correlationId = request.getHeader(HEADER_NAME);
		if (correlationId == null || correlationId.isBlank()) {
			correlationId = "corr-" + UUID.randomUUID().toString().replace("-", "");
		}
		request.setAttribute(ATTRIBUTE_NAME, correlationId);
		response.setHeader(HEADER_NAME, correlationId);
		filterChain.doFilter(request, response);
	}

	public static String getCorrelationId(HttpServletRequest request) {
		Object value = request.getAttribute(ATTRIBUTE_NAME);
		return value instanceof String string ? string : null;
	}
}
