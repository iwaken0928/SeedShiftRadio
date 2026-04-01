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
				"2026-04",
				new SettingsDocument.ServerSettings("127.0.0.1", 18080),
				new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("data").resolve("library").resolve("music").toString()),
				null,
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
						"2026-04",
						null,
						new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("outside").toString()),
						null,
						null,
						null,
						null,
						null,
						null)));

		assertEquals("VALIDATION_ERROR", exception.getCode());
	}

	@Test
	void updateSettingsRejectsSchemaVersionMismatch() {
		settingsService.getSettings();

		ApiException exception = assertThrows(
				ApiException.class,
				() -> settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
						1,
						"2026-02",
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null)));

		assertEquals("VALIDATION_ERROR", exception.getCode());
	}

	@Test
	void updateSettingsRejectsUnknownFallbackProvider() {
		SettingsDtos.SettingsResponse current = settingsService.getSettings();

		ApiException exception = assertThrows(
				ApiException.class,
				() -> settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
						current.version(),
						current.schemaVersion(),
						current.server(),
						new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("data").resolve("library").resolve("music").toString()),
						current.playout(),
						current.cache(),
						current.programming(),
						new SettingsDocument.ProviderCatalog(
								new SettingsDocument.ProviderGroup(
										"ollama",
										List.of("missing-llm"),
										Map.of("ollama", new SettingsDocument.ProviderEndpoint("http://127.0.0.1:11434", "/api/tags", 5_000, List.of("SCRIPT_GEN")))),
								current.providers().tts(),
								current.providers().musicGen()),
						current.security(),
						current.features())));

		assertEquals("VALIDATION_ERROR", exception.getCode());
	}

	@Test
	void updateSettingsPreservesProviderFallbackProviders() {
		SettingsDtos.SettingsResponse current = settingsService.getSettings();
		SettingsDocument.ProviderEndpoint ollamaPrimary = new SettingsDocument.ProviderEndpoint(
				"http://127.0.0.1:11434",
				"/api/tags",
				5_000,
				List.of("SCRIPT_GEN"));
		SettingsDocument.ProviderEndpoint ollamaFallback = new SettingsDocument.ProviderEndpoint(
				"http://127.0.0.1:11435",
				"/api/tags",
				5_000,
				List.of("SCRIPT_GEN"));

		SettingsDtos.SettingsResponse response = settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
				current.version(),
				current.schemaVersion(),
				current.server(),
				new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("data").resolve("library").resolve("music").toString()),
				current.playout(),
				current.cache(),
				current.programming(),
				new SettingsDocument.ProviderCatalog(
						new SettingsDocument.ProviderGroup(
								"ollama",
								List.of("ollama-fallback"),
								Map.of(
										"ollama", ollamaPrimary,
										"ollama-fallback", ollamaFallback)),
						current.providers().tts(),
						current.providers().musicGen()),
				current.security(),
				current.features()));

		assertEquals(List.of("ollama-fallback"), response.providers().llm().fallbackProviders());
		assertTrue(response.providers().llm().providers().containsKey("ollama-fallback"));
	}

	@Test
	void updateSettingsPersistsCacheConfiguration() {
		SettingsDtos.SettingsResponse current = settingsService.getSettings();
		SettingsDocument.CacheSettings updatedCache = new SettingsDocument.CacheSettings(
				64_000L,
				128_000L,
				256_000L,
				3,
				7,
				14,
				"SESSION",
				"STATION",
				"DISABLED",
				50);

		SettingsDtos.SettingsResponse response = settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
				current.version(),
				current.schemaVersion(),
				current.server(),
				new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("data").resolve("library").resolve("music").toString()),
				current.playout(),
				updatedCache,
				current.programming(),
				current.providers(),
				current.security(),
				current.features()));

		assertEquals("SESSION", response.cache().scriptReuseScope());
		assertEquals("DISABLED", response.cache().musicReuseScope());
		assertEquals(50, response.cache().cleanupBatchSize());
		assertEquals(256_000L, response.cache().musicMaxBytes());
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
