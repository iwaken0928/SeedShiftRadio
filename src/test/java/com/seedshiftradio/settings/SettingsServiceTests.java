package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seedshiftradio.common.api.ApiException;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTests {

	@TempDir
	Path tempDir;

	@Mock
	ProviderHealthService providerHealthService;

	SettingsService settingsService;
	Path configPath;

	@BeforeEach
	void setUp() {
		configPath = tempDir.resolve("config").resolve("config.json");
		RadioSettingsStore store = new RadioSettingsStore(new ObjectMapper().findAndRegisterModules(), new RadioConfigProperties(configPath.toString()));
		settingsService = new SettingsService(store, providerHealthService);
	}

	@Test
	void getSettingsCreatesDefaultConfigFile() {
		SettingsDtos.SettingsResponse response = settingsService.getSettings();

		assertEquals(1, response.version());
		assertEquals("127.0.0.1", response.server().bindHost());
		assertTrue(Files.exists(configPath));
	}

	@Test
	void updateSettingsIncrementsVersion() {
		settingsService.getSettings();

		SettingsDtos.SettingsResponse response = settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
				1,
				new SettingsDocument.ServerSettings("127.0.0.1", 18080),
				new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("data").resolve("library").resolve("music").toString()),
				null,
				null,
				null,
				null,
				null));

		assertEquals(2, response.version());
		assertEquals(18080, response.server().port());
	}

	@Test
	void updateSettingsRejectsMusicLibraryOutsideDataRoot() {
		settingsService.getSettings();

		ApiException exception = assertThrows(
				ApiException.class,
				() -> settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
						1,
						null,
						new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("outside").toString()),
						null,
						null,
						null,
						null,
						null)));

		assertEquals("VALIDATION_ERROR", exception.getCode());
	}

	@Test
	void testConnectionsReturnsLatestProviderHealth() {
		Map<String, SettingsDtos.ProviderHealthPayload> providerHealth = Map.of(
				"llm", new SettingsDtos.ProviderHealthPayload("llm", "ollama", "UP", Instant.parse("2026-03-20T09:00:00Z"), 12L, "接続成功", List.of("SCRIPT_GEN"), "http://127.0.0.1:11434"),
				"tts", new SettingsDtos.ProviderHealthPayload("tts", "voicevox", "DOWN", Instant.parse("2026-03-20T09:00:01Z"), 20L, "PROVIDER_UNREACHABLE", List.of("TTS_GEN"), "http://127.0.0.1:50021"));
		when(providerHealthService.refreshHealth()).thenReturn(providerHealth);

		SettingsDtos.ConnectionTestResponse response = settingsService.testConnections();

		assertEquals(providerHealth, response.providers());
		assertEquals(Instant.parse("2026-03-20T09:00:01Z"), response.checkedAt());
	}
}
