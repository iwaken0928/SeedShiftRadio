package com.seedshiftradio.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.PlayoutState;
import com.seedshiftradio.letter.LetterService;
import com.seedshiftradio.monitor.MonitorDtos.MonitorSummaryResponse;
import com.seedshiftradio.radio.RadioService;
import com.seedshiftradio.radio.RadioStatusResponse;
import com.seedshiftradio.settings.ProviderHealthService;
import com.seedshiftradio.settings.SettingsDtos;

@ExtendWith(MockitoExtension.class)
class MonitorServiceTests {

	@Mock
	RadioService radioService;

	@Mock
	LetterService letterService;

	@Mock
	ProviderHealthService providerHealthService;

	MonitorService monitorService;

	@BeforeEach
	void setUp() {
		monitorService = new MonitorService(radioService, letterService, providerHealthService);
	}

	@Test
	void summaryIncludesProviderHealthAndPendingLetters() {
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
				"llm", new SettingsDtos.ProviderHealthPayload("llm", "ollama", "UP", Instant.parse("2026-03-20T09:00:00Z"), 12L, "接続成功", java.util.List.of("SCRIPT_GEN"), "http://127.0.0.1:11434"),
				"tts", new SettingsDtos.ProviderHealthPayload("tts", "voicevox", "DOWN", Instant.parse("2026-03-20T09:00:00Z"), 31L, "PROVIDER_UNREACHABLE", java.util.List.of("TTS_GEN"), "http://127.0.0.1:50021"),
				"musicGen", new SettingsDtos.ProviderHealthPayload("musicGen", "ace-step", "DEGRADED", Instant.parse("2026-03-20T09:00:00Z"), 48L, "HTTP 503", java.util.List.of("MUSIC_GEN"), "http://127.0.0.1:8000"));
		when(radioService.getStatus()).thenReturn(status);
		when(letterService.countPendingLetters("station-night")).thenReturn(5L);
		when(providerHealthService.getLatestOrProbe()).thenReturn(providerHealth);

		MonitorSummaryResponse summary = monitorService.summary();

		assertEquals("playout-001", summary.sessionId());
		assertEquals(5L, summary.pendingLetterCount());
		assertEquals(status.degraded(), summary.degraded());
		assertEquals(providerHealth, summary.providerHealth());
	}
}
