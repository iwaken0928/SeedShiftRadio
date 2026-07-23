package com.seedshiftradio.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.PlayoutState;
import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.PlayHistoryResultStatus;
import com.seedshiftradio.domain.ProviderJobStatus;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.letter.LetterService;
import com.seedshiftradio.monitor.MonitorDtos.AuditEventSummary;
import com.seedshiftradio.monitor.MonitorDtos.AssetConsistencyResponse;
import com.seedshiftradio.monitor.MonitorDtos.MonitorSummaryResponse;
import com.seedshiftradio.monitor.MonitorDtos.ProviderJobSummary;
import com.seedshiftradio.radio.BroadcastArchiveRepository;
import com.seedshiftradio.radio.PlayHistoryRepository;
import com.seedshiftradio.radio.QueueItemRepository;
import com.seedshiftradio.radio.RadioEventRecord;
import com.seedshiftradio.radio.RadioService;
import com.seedshiftradio.radio.RadioStatusResponse;
import com.seedshiftradio.radio.SubtitlePayload;
import com.seedshiftradio.settings.GeneratedAssetService;
import com.seedshiftradio.settings.ProviderJobEntity;
import com.seedshiftradio.settings.ProviderJobRepository;
import com.seedshiftradio.settings.ProviderHealthService;
import com.seedshiftradio.settings.SettingsDtos;
import com.seedshiftradio.stream.StreamEventService;

@ExtendWith(MockitoExtension.class)
class MonitorServiceTests {

	@Mock
	RadioService radioService;

	@Mock
	LetterService letterService;

	@Mock
	ProviderHealthService providerHealthService;

	@Mock
	ProviderJobRepository providerJobRepository;

	@Mock
	GeneratedAssetService generatedAssetService;

	@Mock
	StreamEventService streamEventService;

	@Mock
	QueueItemRepository queueItemRepository;

	@Mock
	BroadcastArchiveRepository broadcastArchiveRepository;

	@Mock
	PlayHistoryRepository playHistoryRepository;

	MonitorService monitorService;

	@BeforeEach
	void setUp() {
		monitorService = new MonitorService(
				radioService,
				letterService,
				providerHealthService,
				providerJobRepository,
				generatedAssetService,
				streamEventService,
				queueItemRepository,
				broadcastArchiveRepository,
				playHistoryRepository);
	}

	@Test
	void summaryIncludesJobsAndAuditEvents() {
		RadioStatusResponse status = new RadioStatusResponse(
				"playout-001",
				"station-night",
				"program-001",
				"tmpl-night-regular",
				"深夜の作業ノート",
				PlayoutState.PLAYING,
				"queue-001",
				2,
				false,
				Instant.parse("2026-03-20T09:00:00Z"),
				"corr-001");
		Map<String, SettingsDtos.ProviderHealthPayload> providerHealth = Map.of(
				"llm", new SettingsDtos.ProviderHealthPayload("llm", "ollama", "UP", Instant.parse("2026-03-20T09:00:00Z"), 12L, "接続成功", List.of("SCRIPT_GEN"), "http://127.0.0.1:11434"),
				"tts", new SettingsDtos.ProviderHealthPayload("tts", "voicevox", "DOWN", Instant.parse("2026-03-20T09:00:00Z"), 31L, "PROVIDER_UNREACHABLE", List.of("TTS_GEN"), "http://127.0.0.1:50021"));

		when(radioService.getStatus()).thenReturn(status);
		when(letterService.countPendingLetters("station-night")).thenReturn(5L);
		when(providerHealthService.getLatestOrProbe()).thenReturn(providerHealth);
		when(generatedAssetService.cacheMetrics()).thenReturn(cacheMetrics());
		when(queueItemRepository.sumDurationMsBySessionIdAndStatus("playout-001", QueueItemStatus.READY)).thenReturn(90_000L);
		when(broadcastArchiveRepository.countEligibleArchivesByStationId(eq("station-night"), any(Instant.class))).thenReturn(3L);
		when(broadcastArchiveRepository.countByStationId("station-night")).thenReturn(4L);
		when(playHistoryRepository.countByStationIdAndResultStatus("station-night", PlayHistoryResultStatus.DONE)).thenReturn(12L);
		when(playHistoryRepository.countByStationIdAndResultStatusAndContentOrigin("station-night", PlayHistoryResultStatus.DONE, "ARCHIVE_REPLAY")).thenReturn(3L);
		when(providerJobRepository.findTop10ByStatusOrderByUpdatedAtDesc(ProviderJobStatus.RUNNING)).thenReturn(List.of(job("job-running", ProviderJobStatus.RUNNING, ProviderJobType.MUSIC_GEN)));
		when(providerJobRepository.findTop10ByStatusOrderByUpdatedAtDesc(ProviderJobStatus.FAILED)).thenReturn(List.of(job("job-failed", ProviderJobStatus.FAILED, ProviderJobType.TTS_GEN)));
		when(streamEventService.recentEvents(20)).thenReturn(List.of(
				new RadioEventRecord("12", "provider.job.failed", Instant.parse("2026-03-20T09:15:00Z"), Map.of(
						"providerJobId", "job-failed",
						"jobType", "TTS_GEN",
						"status", "FAILED",
						"errorCode", "PROVIDER_TIMEOUT")),
				new RadioEventRecord("11", "buffer.warning", Instant.parse("2026-03-20T09:14:00Z"), Map.of(
						"sessionId", "playout-001",
						"readyCount", 1)),
				new RadioEventRecord("10", "subtitle.updated", Instant.parse("2026-03-20T09:13:00Z"),
						new SubtitlePayload("playout-001", "queue-001", "sd-queue-001", "秘密の本文をここには残しません", Instant.parse("2026-03-20T09:13:00Z")))));

		MonitorSummaryResponse summary = monitorService.summary();

		assertEquals("playout-001", summary.sessionId());
		assertEquals(90_000L, summary.queueReadyDurationMs());
		assertEquals(5L, summary.pendingLetterCount());
		assertEquals(status.degraded(), summary.degraded());
		assertEquals(providerHealth, summary.providerHealth());
		assertEquals(12_345L, summary.cache().byteSize());
		assertEquals(2, summary.cache().byType().get(GeneratedAssetType.MUSIC).assetCount());
		assertEquals(0.25D, summary.cache().cacheHitRate());
		assertEquals(3L, summary.archive().eligibleArchiveCount());
		assertEquals(4L, summary.archive().totalArchiveCount());
		assertEquals(3L, summary.archive().archiveReplayCount());
		assertEquals(12L, summary.archive().totalPlaybackCount());
		assertEquals(0.25D, summary.archive().archiveReplayRate());
		assertEquals(1, summary.runningJobs().size());
		assertEquals("job-running", summary.runningJobs().getFirst().id());
		assertEquals(1, summary.recentErrors().size());
		assertEquals("job-failed", summary.recentErrors().getFirst().id());
		assertEquals("PROVIDER_INTERRUPTED", summary.recentErrors().getFirst().errorCode());
		assertEquals(3, summary.auditEvents().size());
		assertEquals("provider.job.failed", summary.auditEvents().getFirst().eventType());
		assertEquals("buffer.warning", summary.auditEvents().get(1).eventType());
		assertEquals("subtitle.updated", summary.auditEvents().getLast().eventType());
		assertTrue(summary.auditEvents().getLast().summary().contains("textHash="));
		assertFalse(summary.auditEvents().getLast().summary().contains("秘密の本文"));
	}

	@Test
	void summaryDoesNotLeakSensitiveProviderJobPayloadIntoAuditSummary() {
		RadioStatusResponse status = new RadioStatusResponse(
				"playout-001",
				"station-night",
				"program-001",
				"tmpl-night-regular",
				"深夜の作業ノート",
				PlayoutState.PLAYING,
				"queue-001",
				2,
				false,
				Instant.parse("2026-03-20T09:00:00Z"),
				"corr-001");
		when(radioService.getStatus()).thenReturn(status);
		when(letterService.countPendingLetters("station-night")).thenReturn(0L);
		when(providerHealthService.getLatestOrProbe()).thenReturn(Map.of());
		when(generatedAssetService.cacheMetrics()).thenReturn(cacheMetrics());
		when(queueItemRepository.sumDurationMsBySessionIdAndStatus("playout-001", QueueItemStatus.READY)).thenReturn(0L);
		when(broadcastArchiveRepository.countEligibleArchivesByStationId(eq("station-night"), any(Instant.class))).thenReturn(0L);
		when(broadcastArchiveRepository.countByStationId("station-night")).thenReturn(0L);
		when(playHistoryRepository.countByStationIdAndResultStatus("station-night", PlayHistoryResultStatus.DONE)).thenReturn(0L);
		when(playHistoryRepository.countByStationIdAndResultStatusAndContentOrigin("station-night", PlayHistoryResultStatus.DONE, "ARCHIVE_REPLAY")).thenReturn(0L);
		when(providerJobRepository.findTop10ByStatusOrderByUpdatedAtDesc(ProviderJobStatus.RUNNING)).thenReturn(List.of());
		when(providerJobRepository.findTop10ByStatusOrderByUpdatedAtDesc(ProviderJobStatus.FAILED)).thenReturn(List.of());
		when(streamEventService.recentEvents(20)).thenReturn(List.of(
				new RadioEventRecord("12", "provider.job.failed", Instant.parse("2026-03-20T09:15:00Z"), Map.of(
						"providerJobId", "job-failed",
						"jobType", "TTS_GEN",
						"status", "FAILED",
						"errorCode", "PROVIDER_TIMEOUT",
						"prompt", "raw prompt",
						"lyrics", "raw lyrics",
						"adminToken", "super-secret"))));

		MonitorSummaryResponse summary = monitorService.summary();

		assertEquals(1, summary.auditEvents().size());
		String auditSummary = summary.auditEvents().getFirst().summary();
		assertTrue(auditSummary.contains("job=job-failed"));
		assertFalse(auditSummary.contains("raw prompt"));
		assertFalse(auditSummary.contains("raw lyrics"));
		assertFalse(auditSummary.contains("super-secret"));
	}

	@Test
	void assetConsistencyDelegatesToGeneratedAssetService() {
		GeneratedAssetService.AssetConsistencyReport report = new GeneratedAssetService.AssetConsistencyReport(
				Instant.parse("2026-03-20T09:30:00Z"),
				1L,
				1L,
				1L,
				0L,
				0L,
				0L,
				0L,
				1L,
				false,
				List.of(new GeneratedAssetService.AssetConsistencyIssue(
						GeneratedAssetService.AssetConsistencyIssueType.MISSING_FILE,
						"asset-missing",
						GeneratedAssetType.AUDIO,
						"assets/audio/missing.wav",
						123L,
						null,
						"payload file が通常ファイルとして存在しません。")));
		when(generatedAssetService.assetConsistency()).thenReturn(report);

		AssetConsistencyResponse response = monitorService.assetConsistency();

		assertEquals(report.checkedAt(), response.checkedAt());
		assertEquals(report.assetCount(), response.assetCount());
		assertSame(report.issues().getFirst(), response.issues().getFirst());
		verify(generatedAssetService).assetConsistency();
	}

	private ProviderJobEntity job(String id, ProviderJobStatus status, ProviderJobType jobType) {
		ProviderJobEntity entity = newProviderJobEntity();
		entity.setId(id);
		entity.setStatus(status);
		entity.setJobType(jobType);
		entity.setProviderType(ProviderType.MUSIC);
		entity.setProviderKey("ace-step");
		entity.setQueueItemId("queue-001");
		entity.setExternalRef("worker-job-001");
		entity.setErrorCode(status == ProviderJobStatus.FAILED ? "PROVIDER_INTERRUPTED" : null);
		entity.setCorrelationId("corr-001");
		entity.setCreatedAt(Instant.parse("2026-03-20T09:00:00Z"));
		entity.setUpdatedAt(Instant.parse("2026-03-20T09:10:00Z"));
		entity.setStartedAt(Instant.parse("2026-03-20T09:05:00Z"));
		entity.setEndedAt(status == ProviderJobStatus.FAILED ? Instant.parse("2026-03-20T09:10:00Z") : null);
		return entity;
	}

	private GeneratedAssetService.CacheMetricsSnapshot cacheMetrics() {
		return new GeneratedAssetService.CacheMetricsSnapshot(
				Instant.parse("2026-03-20T09:00:00Z"),
				4L,
				12_345L,
				1L,
				0.25D,
				1L,
				Map.of(
						GeneratedAssetType.SCRIPT, new GeneratedAssetService.CacheTypeMetrics(GeneratedAssetType.SCRIPT, 1L, 123L, 0L, 0.0D),
						GeneratedAssetType.AUDIO, new GeneratedAssetService.CacheTypeMetrics(GeneratedAssetType.AUDIO, 1L, 456L, 0L, 0.0D),
						GeneratedAssetType.MUSIC, new GeneratedAssetService.CacheTypeMetrics(GeneratedAssetType.MUSIC, 2L, 11_766L, 1L, 0.33D)));
	}

	private ProviderJobEntity newProviderJobEntity() {
		try {
			Constructor<ProviderJobEntity> constructor = ProviderJobEntity.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("ProviderJobEntity を生成できません", ex);
		}
	}
}
