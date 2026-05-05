package com.seedshiftradio.monitor;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.radio.HealthResponse;

@ExtendWith(MockitoExtension.class)
class HealthControllerTests {

	@Mock
	HealthService healthService;

	HealthController controller;

	@BeforeEach
	void setUp() {
		controller = new HealthController(healthService);
	}

	@Test
	void healthDelegatesToHealthService() {
		HealthResponse sample = new HealthResponse(
				"UP",
				Instant.parse("2026-03-20T09:00:00Z"),
				1,
				1,
				1,
				"latest-event",
				"playout-001",
				Map.of());
		when(healthService.health()).thenReturn(sample);

		HealthResponse response = controller.health();

		assertSame(sample, response);
	}
}
