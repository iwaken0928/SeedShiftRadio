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
		assertEquals("VOICEVOX", response.providers().tts().providers().get("voicevox").adapter());
		assertEquals(3, response.playout().targetReadyCount());
		assertEquals(2, response.playout().minimumReadyCount());
		assertEquals(90_000, response.playout().minReadyDurationMs());
		assertEquals(480_000, response.playout().maxPreparedDurationMs());
		assertEquals(2, response.playout().maxPreparedBlocks());
		assertEquals(4, response.playout().scriptAheadCount());
		assertEquals(3, response.playout().ttsAheadCount());
		assertEquals(2, response.playout().musicAheadCount());
		assertTrue(response.playout().idlePrefetchEnabled());
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
	void updateSettingsRejectsNonWavMusicOutputFormat() {
		SettingsDtos.SettingsResponse current = settingsService.getSettings();
		SettingsDocument.ProviderEndpoint mp3Endpoint = new SettingsDocument.ProviderEndpoint(
				"http://127.0.0.1:8001",
				"/health",
				5_000,
				List.of("MUSIC_GEN", "ACE_STEP"),
				"ACE_STEP",
				null,
				"mp3-profile",
				Map.of("mp3-profile", new SettingsDocument.MusicGenerationModelProfile(
						"acestep-v15-turbo",
						"acestep-5Hz-lm-0.6B",
						true,
						"ja",
						"native",
						"mp3",
						120)));

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
								current.providers().llm(),
								current.providers().tts(),
								new SettingsDocument.ProviderGroup("mp3-profile", List.of(), Map.of("mp3-profile", mp3Endpoint))),
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
	void updateSettingsRejectsNonSecretAdminTokenRef() {
		SettingsDtos.SettingsResponse current = settingsService.getSettings();

		ApiException exception = assertThrows(
				ApiException.class,
				() -> settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
						current.version(),
						current.schemaVersion(),
						current.server(),
						current.paths(),
						current.playout(),
						current.cache(),
						current.programming(),
						current.providers(),
						new SettingsDocument.SecuritySettings("plain-admin-token"),
						current.features())));

		assertEquals("VALIDATION_ERROR", exception.getCode());
		assertEquals("security.adminTokenRef", exception.getDetails().get("field"));
	}

	@Test
	void updateSettingsRejectsNonSecretApiKeyRefForLlmAndTtsProviders() {
		SettingsDtos.SettingsResponse current = settingsService.getSettings();
		SettingsDocument.ProviderEndpoint llmEndpoint = new SettingsDocument.ProviderEndpoint(
				"http://127.0.0.1:11434",
				"/api/tags",
				5_000,
				List.of("SCRIPT_GEN"),
				null,
				"plain-llm-key",
				null,
				null);
		SettingsDocument.ProviderEndpoint ttsEndpoint = new SettingsDocument.ProviderEndpoint(
				"http://127.0.0.1:50021",
				"/version",
				5_000,
				List.of("TTS_GEN"),
				null,
				"plain-tts-key",
				null,
				null);

		ApiException llmException = assertThrows(
				ApiException.class,
				() -> settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
						current.version(),
						current.schemaVersion(),
						current.server(),
						current.paths(),
						current.playout(),
						current.cache(),
						current.programming(),
						new SettingsDocument.ProviderCatalog(
								new SettingsDocument.ProviderGroup("ollama", List.of(), Map.of("ollama", llmEndpoint)),
								current.providers().tts(),
								current.providers().musicGen()),
						current.security(),
						current.features())));
		assertEquals("VALIDATION_ERROR", llmException.getCode());
		assertEquals("providers.llm.ollama.apiKeyRef", llmException.getDetails().get("field"));

		ApiException ttsException = assertThrows(
				ApiException.class,
				() -> settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
						current.version(),
						current.schemaVersion(),
						current.server(),
						current.paths(),
						current.playout(),
						current.cache(),
						current.programming(),
						new SettingsDocument.ProviderCatalog(
								current.providers().llm(),
								new SettingsDocument.ProviderGroup("voicevox", List.of(), Map.of("voicevox", ttsEndpoint)),
								current.providers().musicGen()),
						current.security(),
						current.features())));
		assertEquals("VALIDATION_ERROR", ttsException.getCode());
		assertEquals("providers.tts.voicevox.apiKeyRef", ttsException.getDetails().get("field"));
	}

	@Test
	void updateSettingsValidatesTtsProviderAdapterSeparatelyFromMusicGen() {
		SettingsDtos.SettingsResponse current = settingsService.getSettings();
		SettingsDocument.ProviderEndpoint irodoriEndpoint = new SettingsDocument.ProviderEndpoint(
				"http://127.0.0.1:8088",
				"/health",
				180_000,
				List.of("TTS_GEN", "IRODORI_TTS"),
				"IRODORI_OPENAI_TTS",
				"env:IRODORI_TTS_API_KEY",
				"irodori-tts",
				null);

		SettingsDtos.SettingsResponse response = settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
				current.version(),
				current.schemaVersion(),
				current.server(),
				current.paths(),
				current.playout(),
				current.cache(),
				current.programming(),
				new SettingsDocument.ProviderCatalog(
						current.providers().llm(),
						new SettingsDocument.ProviderGroup(
								"irodori",
								List.of("voicevox"),
								Map.of(
										"irodori", irodoriEndpoint,
										"voicevox", current.providers().tts().providers().get("voicevox"))),
						current.providers().musicGen()),
				current.security(),
				current.features()));

		assertEquals("IRODORI_OPENAI_TTS", response.providers().tts().providers().get("irodori").adapter());
		assertEquals("irodori-tts", response.providers().tts().providers().get("irodori").defaultModelProfileId());
	}

	@Test
	void updateSettingsRejectsUnsupportedTtsProviderAdapter() {
		SettingsDtos.SettingsResponse current = settingsService.getSettings();
		SettingsDocument.ProviderEndpoint invalidEndpoint = new SettingsDocument.ProviderEndpoint(
				"http://127.0.0.1:50021",
				"/version",
				5_000,
				List.of("TTS_GEN"),
				"ACE_STEP",
				null,
				null,
				null);

		ApiException exception = assertThrows(
				ApiException.class,
				() -> settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
						current.version(),
						current.schemaVersion(),
						current.server(),
						current.paths(),
						current.playout(),
						current.cache(),
						current.programming(),
						new SettingsDocument.ProviderCatalog(
								current.providers().llm(),
								new SettingsDocument.ProviderGroup("voicevox", List.of(), Map.of("voicevox", invalidEndpoint)),
								current.providers().musicGen()),
						current.security(),
						current.features())));

		assertEquals("VALIDATION_ERROR", exception.getCode());
		assertEquals("providers.tts.voicevox.adapter", exception.getDetails().get("field"));
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
	void updateSettingsPersistsPlayoutConfiguration() {
		SettingsDtos.SettingsResponse current = settingsService.getSettings();
		SettingsDocument.PlayoutSettings updatedPlayout = new SettingsDocument.PlayoutSettings(
				5,
				4,
				120_000,
				600_000,
				3,
				6,
				5,
				4,
				false);

		SettingsDtos.SettingsResponse response = settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
				current.version(),
				current.schemaVersion(),
				current.server(),
				current.paths(),
				updatedPlayout,
				current.cache(),
				current.programming(),
				current.providers(),
				current.security(),
				current.features()));

		assertEquals(5, response.playout().targetReadyCount());
		assertEquals(4, response.playout().minimumReadyCount());
		assertEquals(120_000, response.playout().minReadyDurationMs());
		assertEquals(600_000, response.playout().maxPreparedDurationMs());
		assertEquals(3, response.playout().maxPreparedBlocks());
		assertEquals(6, response.playout().scriptAheadCount());
		assertEquals(5, response.playout().ttsAheadCount());
		assertEquals(4, response.playout().musicAheadCount());
		assertEquals(false, response.playout().idlePrefetchEnabled());
	}

	@Test
	void updateSettingsRejectsMinimumReadyCountAboveTargetReadyCount() {
		SettingsDtos.SettingsResponse current = settingsService.getSettings();

		ApiException exception = assertThrows(
				ApiException.class,
				() -> settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
						current.version(),
						current.schemaVersion(),
						current.server(),
						current.paths(),
						new SettingsDocument.PlayoutSettings(3, 4, 90_000, 480_000, 2, 4, 3, 2, true),
						current.cache(),
						current.programming(),
						current.providers(),
						current.security(),
						current.features())));

		assertEquals("VALIDATION_ERROR", exception.getCode());
	}

	@Test
	void updateSettingsRejectsMaxPreparedDurationBelowMinimumReadyDuration() {
		SettingsDtos.SettingsResponse current = settingsService.getSettings();

		ApiException exception = assertThrows(
				ApiException.class,
				() -> settingsService.updateSettings(new SettingsDtos.SettingsUpdateRequest(
						current.version(),
						current.schemaVersion(),
						current.server(),
						current.paths(),
						new SettingsDocument.PlayoutSettings(3, 2, 90_000, 60_000, 2, 4, 3, 2, true),
						current.cache(),
						current.programming(),
						current.providers(),
						current.security(),
						current.features())));

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
