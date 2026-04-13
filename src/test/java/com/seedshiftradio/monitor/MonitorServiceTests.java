package com.seedshiftradio.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.seedshiftradio.domain.ProviderJobStatus;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.letter.LetterService;
import com.seedshiftradio.monitor.MonitorDtos.AuditEventSummary;
import com.seedshiftradio.monitor.MonitorDtos.MonitorSummaryResponse;
import com.seedshiftradio.monitor.MonitorDtos.ProviderJobSummary;
import com.seedshiftradio.radio.RadioEventRecord;
import com.seedshiftradio.radio.RadioService;
import com.seedshiftradio.radio.RadioStatusResponse;
import com.seedshiftradio.radio.SubtitlePayload;
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
	StreamEventService streamEventService;

	MonitorService monitorService;

	@BeforeEach
	void setUp() {
		monitorService = new MonitorService(radioService, letterService, providerHealthService, providerJobRepository, streamEventService);
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
		assertEquals(5L, summary.pendingLetterCount());
		assertEquals(status.degraded(), summary.degraded());
		assertEquals(providerHealth, summary.providerHealth());
		assertEquals(1, summary.runningJobs().size());
		assertEquals("job-running", summary.runningJobs().getFirst().id());
		assertEquals(1, summary.recentErrors().size());
		assertEquals("job-failed", summary.recentErrors().getFirst().id());
		assertEquals(3, summary.auditEvents().size());
		assertEquals("provider.job.failed", summary.auditEvents().getFirst().eventType());
		assertEquals("buffer.warning", summary.auditEvents().get(1).eventType());
		assertEquals("subtitle.updated", summary.auditEvents().getLast().eventType());
		assertTrue(summary.auditEvents().getLast().summary().contains("textHash="));
		assertFalse(summary.auditEvents().getLast().summary().contains("秘密の本文"));
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
		entity.setErrorCode(status == ProviderJobStatus.FAILED ? "PROVIDER_TIMEOUT" : null);
		entity.setCorrelationId("corr-001");
		entity.setCreatedAt(Instant.parse("2026-03-20T09:00:00Z"));
		entity.setUpdatedAt(Instant.parse("2026-03-20T09:10:00Z"));
		entity.setStartedAt(Instant.parse("2026-03-20T09:05:00Z"));
		entity.setEndedAt(status == ProviderJobStatus.FAILED ? Instant.parse("2026-03-20T09:10:00Z") : null);
		return entity;
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
