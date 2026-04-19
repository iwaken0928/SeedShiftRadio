package com.seedshiftradio.monitor;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.seedshiftradio.common.security.AdminApiGuard;
import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.PlayoutState;
import com.seedshiftradio.monitor.MonitorDtos.ArchiveMetrics;
import com.seedshiftradio.monitor.MonitorDtos.MonitorSummaryResponse;
import com.seedshiftradio.settings.GeneratedAssetService;

@ExtendWith(MockitoExtension.class)
class MonitorControllerTests {

	@Mock
	MonitorService monitorService;

	@Mock
	AdminApiGuard adminApiGuard;

	MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new MonitorController(monitorService, adminApiGuard)).build();
	}

	@Test
	void summaryReturnsQueueDurationAndArchiveMetrics() throws Exception {
		when(monitorService.summary()).thenReturn(summary());

		mockMvc.perform(get("/api/monitor/summary").header(AdminApiGuard.HEADER_NAME, "test-admin-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sessionId").value("playout-001"))
				.andExpect(jsonPath("$.queueReadyDurationMs").value(90_000))
				.andExpect(jsonPath("$.archive.eligibleArchiveCount").value(3))
				.andExpect(jsonPath("$.archive.totalArchiveCount").value(4))
				.andExpect(jsonPath("$.archive.archiveReplayCount").value(3))
				.andExpect(jsonPath("$.archive.totalPlaybackCount").value(12))
				.andExpect(jsonPath("$.archive.archiveReplayRate").value(0.25));

		verify(adminApiGuard).require("test-admin-token");
	}

	private MonitorSummaryResponse summary() {
		return new MonitorSummaryResponse(
				"playout-001",
				"station-night",
				PlayoutState.PLAYING,
				2,
				90_000L,
				5L,
				false,
				Map.of(),
				cacheMetrics(),
				new ArchiveMetrics(3L, 4L, 3L, 12L, 0.25D),
				List.of(),
				List.of(),
				List.of(),
				Instant.parse("2026-03-20T09:00:00Z"));
	}

	private GeneratedAssetService.CacheMetricsSnapshot cacheMetrics() {
		return new GeneratedAssetService.CacheMetricsSnapshot(
				Instant.parse("2026-03-20T09:00:00Z"),
				0L,
				0L,
				0L,
				0.0D,
				0L,
				Map.of(
						GeneratedAssetType.MUSIC,
						new GeneratedAssetService.CacheTypeMetrics(GeneratedAssetType.MUSIC, 0L, 0L, 0L, 0.0D)));
	}
}
