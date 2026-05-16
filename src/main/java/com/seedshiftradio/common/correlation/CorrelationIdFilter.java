package com.seedshiftradio.common.correlation;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

	public static final String HEADER_NAME = "X-Correlation-Id";
	public static final String ATTRIBUTE_NAME = "seedshiftRadioCorrelationId";

	private static final String MDC_CORRELATION_ID = "correlationId";
	private static final String MDC_STATION_ID = "stationId";
	private static final String MDC_SESSION_ID = "sessionId";
	private static final String MDC_QUEUE_ITEM_ID = "queueItemId";
	private static final String MDC_PROGRAM_BLOCK_ID = "programBlockId";
	private static final String[] MDC_KEYS = {
			MDC_CORRELATION_ID,
			MDC_STATION_ID,
			MDC_SESSION_ID,
			MDC_QUEUE_ITEM_ID,
			MDC_PROGRAM_BLOCK_ID
	};

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		long startedAt = System.nanoTime();
		String correlationId = resolveCorrelationId(request);
		request.setAttribute(ATTRIBUTE_NAME, correlationId);
		response.setHeader(HEADER_NAME, correlationId);
		putMdc(MDC_CORRELATION_ID, correlationId);
		putSafeIdentifierMdc(request);
		try {
			filterChain.doFilter(request, response);
		} finally {
			long elapsedMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
			log.info(
					"http_request_completed method={} path={} status={} elapsedMs={}",
					request.getMethod(),
					request.getRequestURI(),
					response.getStatus(),
					elapsedMs);
			clearMdc();
		}
	}

	public static String getCorrelationId(HttpServletRequest request) {
		Object value = request.getAttribute(ATTRIBUTE_NAME);
		if (value instanceof String string && !string.isBlank()) {
			return string;
		}
		String correlationId = request.getHeader(HEADER_NAME);
		if (correlationId == null || correlationId.isBlank()) {
			correlationId = "corr-" + UUID.randomUUID().toString().replace("-", "");
		}
		request.setAttribute(ATTRIBUTE_NAME, correlationId);
		return correlationId;
	}

	private static String resolveCorrelationId(HttpServletRequest request) {
		String correlationId = request.getHeader(HEADER_NAME);
		if (correlationId == null || correlationId.isBlank()) {
			return "corr-" + UUID.randomUUID().toString().replace("-", "");
		}
		return correlationId;
	}

	private static void putSafeIdentifierMdc(HttpServletRequest request) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put(MDC_STATION_ID, request.getParameter(MDC_STATION_ID));
		values.put(MDC_SESSION_ID, request.getParameter(MDC_SESSION_ID));
		values.put(MDC_QUEUE_ITEM_ID, request.getParameter(MDC_QUEUE_ITEM_ID));
		values.put(MDC_PROGRAM_BLOCK_ID, request.getParameter(MDC_PROGRAM_BLOCK_ID));

		String[] segments = request.getRequestURI().split("/");
		for (int index = 0; index < segments.length - 1; index++) {
			if ("stations".equals(segments[index])) {
				values.putIfAbsent(MDC_STATION_ID, stripExtension(segments[index + 1]));
			}
			if ("sessions".equals(segments[index])) {
				values.putIfAbsent(MDC_SESSION_ID, stripExtension(segments[index + 1]));
			}
			if ("queue-items".equals(segments[index])) {
				values.putIfAbsent(MDC_QUEUE_ITEM_ID, stripExtension(segments[index + 1]));
			}
			if ("program-blocks".equals(segments[index])) {
				values.putIfAbsent(MDC_PROGRAM_BLOCK_ID, stripExtension(segments[index + 1]));
			}
		}

		values.forEach(CorrelationIdFilter::putMdc);
	}

	private static String stripExtension(String value) {
		int dotIndex = value.indexOf('.');
		return dotIndex >= 0 ? value.substring(0, dotIndex) : value;
	}

	private static void putMdc(String key, String value) {
		if (value != null && !value.isBlank()) {
			MDC.put(key, value);
		}
	}

	private static void clearMdc() {
		for (String key : MDC_KEYS) {
			MDC.remove(key);
		}
	}
}
