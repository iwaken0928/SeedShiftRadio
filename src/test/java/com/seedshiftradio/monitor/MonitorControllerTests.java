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
import com.seedshiftradio.monitor.MonitorDtos.AssetConsistencyResponse;
import com.seedshiftradio.monitor.MonitorDtos.MonitorSummaryResponse;
import com.seedshiftradio.monitor.MonitorDtos.OperationalEventSummary;
import com.seedshiftradio.settings.GeneratedAssetService;

@ExtendWith(MockitoExtension.class)
class MonitorControllerTests {

	@Mock
	MonitorService monitorService;

	@Mock
	AdminApiGuard adminApiGuard;

	@Mock
	OperationalEventService operationalEventService;

	MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(
				new MonitorController(monitorService, operationalEventService, adminApiGuard)).build();
	}

	@Test
	void logsReturnsSafeStructuredEventsAndRequiresAdminToken() throws Exception {
		when(operationalEventService.recent(50)).thenReturn(List.of(
				new OperationalEventSummary(
						"oplog-001",
						"ERROR",
						"PROVIDER_JOB",
						"provider.job.failed",
						"provider-job-001",
						"corr-001",
						"LLM",
						"ollama",
						"PROVIDER_TIMEOUT",
						"Provider がタイムアウトしました。",
						Instant.parse("2026-07-26T04:37:24Z"))));

		mockMvc.perform(get("/api/monitor/logs")
						.param("limit", "50")
						.header(AdminApiGuard.HEADER_NAME, "test-admin-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].category").value("PROVIDER_JOB"))
				.andExpect(jsonPath("$[0].providerKey").value("ollama"))
				.andExpect(jsonPath("$[0].errorCode").value("PROVIDER_TIMEOUT"))
				.andExpect(jsonPath("$[0].correlationId").value("corr-001"));

		verify(adminApiGuard).require("test-admin-token");
		verify(operationalEventService).recent(50);
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

	@Test
	void assetConsistencyReturnsIssueCountsAndRequiresAdminToken() throws Exception {
		when(monitorService.assetConsistency()).thenReturn(assetConsistency());

		mockMvc.perform(get("/api/monitor/assets/consistency").header(AdminApiGuard.HEADER_NAME, "test-admin-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.checkedAt").value("2026-03-20T09:30:00Z"))
				.andExpect(jsonPath("$.assetCount").value(3))
				.andExpect(jsonPath("$.checkedAssetCount").value(2))
				.andExpect(jsonPath("$.missingFileCount").value(1))
				.andExpect(jsonPath("$.byteSizeMismatchCount").value(1))
				.andExpect(jsonPath("$.contentHashMismatchCount").value(0))
				.andExpect(jsonPath("$.orphanFileCount").value(1))
				.andExpect(jsonPath("$.unreadableFileCount").value(0))
				.andExpect(jsonPath("$.issueCount").value(3))
				.andExpect(jsonPath("$.issuesTruncated").value(false))
				.andExpect(jsonPath("$.issues[0].issueType").value("MISSING_FILE"))
				.andExpect(jsonPath("$.issues[0].assetId").value("asset-missing"))
				.andExpect(jsonPath("$.issues[0].storagePath").value("assets/audio/missing.wav"))
				.andExpect(jsonPath("$.issues[0].expectedByteSize").value(123))
				.andExpect(jsonPath("$.issues[1].issueType").value("BYTE_SIZE_MISMATCH"))
				.andExpect(jsonPath("$.issues[1].actualByteSize").value(5));

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

	private AssetConsistencyResponse assetConsistency() {
		return new AssetConsistencyResponse(
				Instant.parse("2026-03-20T09:30:00Z"),
				3L,
				2L,
				1L,
				1L,
				0L,
				1L,
				0L,
				3L,
				false,
				List.of(
						new GeneratedAssetService.AssetConsistencyIssue(
								GeneratedAssetService.AssetConsistencyIssueType.MISSING_FILE,
								"asset-missing",
								GeneratedAssetType.AUDIO,
								"assets/audio/missing.wav",
								123L,
								null,
								"payload file が通常ファイルとして存在しません。"),
						new GeneratedAssetService.AssetConsistencyIssue(
								GeneratedAssetService.AssetConsistencyIssueType.BYTE_SIZE_MISMATCH,
								"asset-mismatch",
								GeneratedAssetType.SCRIPT,
								"assets/scripts/mismatch.txt",
								12L,
								5L,
								"DB metadata の byteSize と payload file size が一致しません。"),
						new GeneratedAssetService.AssetConsistencyIssue(
								GeneratedAssetService.AssetConsistencyIssueType.ORPHAN_FILE,
								null,
								null,
								"assets/music/orphan.wav",
								null,
								456L,
								"generated asset metadata から参照されていない payload file です。")));
	}
}
