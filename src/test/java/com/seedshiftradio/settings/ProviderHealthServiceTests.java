package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seedshiftradio.radio.RadioEventRecord;
import com.seedshiftradio.stream.StreamEventService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

class ProviderHealthServiceTests {

	@TempDir
	Path tempDir;

	HttpServer httpServer;
	ProviderHealthService providerHealthService;
	StreamEventService streamEventService;

	@BeforeEach
	void setUp() throws IOException {
		httpServer = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		httpServer.createContext("/up", new FixedResponseHandler(200, "ok"));
		httpServer.createContext("/error", new FixedResponseHandler(503, "error"));
		httpServer.createContext("/api/tags", new FixedResponseHandler(
				200,
				"{\"models\":[{\"name\":\"qwen3:8b\"},{\"model\":\"gemma3:4b\"}]}"));
		httpServer.createContext("/v1/stats", new FixedResponseHandler(
				200,
				"{\"data\":{\"queue_size\":4,\"avg_job_seconds\":12.5,\"jobs\":{\"queued\":1,\"running\":2}}}"));
		httpServer.createContext("/v1/models", new FixedResponseHandler(
				200,
				"{\"data\":{\"default_model\":\"acestep-v15-turbo\",\"models\":[{\"name\":\"acestep-v15-turbo\"}]}}"));
		httpServer.start();

		String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
		SettingsDocument settings = new SettingsDocument(
				1,
				"2026-04",
				Instant.parse("2026-03-20T09:00:00Z"),
				SettingsDocument.ServerSettings.defaults(),
				new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("data").resolve("library").resolve("music").toString()),
				SettingsDocument.PlayoutSettings.defaults(),
				SettingsDocument.CacheSettings.defaults(),
				SettingsDocument.ProgrammingSettings.defaults(),
				new SettingsDocument.ProviderCatalog(
						new SettingsDocument.ProviderGroup("ollama", List.of(), Map.of("ollama", new SettingsDocument.ProviderEndpoint(
								baseUrl,
								"/up",
								1_000,
								List.of("SCRIPT_GEN"),
								"OLLAMA",
								null,
								"qwen3:8b",
								Map.of()))),
						new SettingsDocument.ProviderGroup("voicevox", List.of(), Map.of("voicevox", new SettingsDocument.ProviderEndpoint(baseUrl, "/error", 1_000, List.of("TTS_GEN")))),
						new SettingsDocument.ProviderGroup(
								"ace-step-primary",
								List.of("ace-step-fallback"),
								Map.of(
										"ace-step-primary", new SettingsDocument.ProviderEndpoint("http://127.0.0.1:1", "/health", 1_000, List.of("MUSIC_GEN")),
										"ace-step-fallback", new SettingsDocument.ProviderEndpoint(
												baseUrl,
												"/up",
												1_000,
												List.of("MUSIC_GEN", "ACE_STEP", "JAPANESE_LYRICS"),
												"ACE_STEP",
												null,
												"ace-ja-fast",
												SettingsDocument.MusicGenerationModelProfile.defaultAceStepProfiles())))),
				SettingsDocument.SecuritySettings.defaults(),
				SettingsDocument.FeatureSettings.defaults())
				.normalize();

		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		RadioSettingsStore store = new RadioSettingsStore(objectMapper, new RadioConfigProperties(tempDir.resolve("config.json").toString()));
		store.save(settings);
		streamEventService = new StreamEventService();
		providerHealthService = new ProviderHealthService(new ProviderRegistry(store), streamEventService, objectMapper);
	}

	@AfterEach
	void tearDown() {
		if (httpServer != null) {
			httpServer.stop(0);
		}
	}

	@Test
	void refreshHealthClassifiesProvidersAndPublishesEvent() {
		Map<String, SettingsDtos.ProviderHealthPayload> response = providerHealthService.refreshHealth();
		List<RadioEventRecord> events = replayAfter("0");

		assertEquals("UP", response.get("llm").status());
		assertEquals(List.of("qwen3:8b", "gemma3:4b"), response.get("llm").metadata().get("models"));
		assertEquals(true, response.get("llm").metadata().get("selectedModelAvailable"));
		assertEquals("DEGRADED", response.get("tts").status());
		assertEquals("VOICEVOX", response.get("tts").metadata().get("adapter"));
		assertEquals(false, response.get("tts").metadata().get("streamingSupported"));
		assertEquals("DEGRADED", response.get("musicGen").status());
		assertEquals("ace-step-fallback", response.get("musicGen").providerKey());
		assertEquals(4, response.get("musicGen").metadata().get("queueSize"));
		assertEquals("acestep-v15-turbo", response.get("musicGen").metadata().get("defaultModel"));
		assertEquals(1, events.size());
		assertEquals("provider.health.changed", events.getFirst().eventType());
		assertEquals(response, events.getFirst().payload());
	}

	@Test
	void refreshHealthReportsConfiguredLlmModelAsDegradedWhenItIsNotInstalled() {
		SettingsDocument settings = SettingsDocument.defaults();
		String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
		SettingsDocument.ProviderEndpoint endpoint = new SettingsDocument.ProviderEndpoint(
				baseUrl,
				"/up",
				1_000,
				List.of("SCRIPT_GEN"),
				"OLLAMA",
				null,
				"missing-model:latest",
				Map.of());
		SettingsDocument configured = new SettingsDocument(
				settings.version(),
				settings.schemaVersion(),
				settings.updatedAt(),
				settings.server(),
				settings.paths(),
				settings.playout(),
				settings.cache(),
				settings.programming(),
				new SettingsDocument.ProviderCatalog(
						new SettingsDocument.ProviderGroup("ollama", List.of(), Map.of("ollama", endpoint)),
						settings.providers().tts(),
						settings.providers().musicGen()),
				settings.security(),
				settings.features()).normalize();
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		RadioSettingsStore store = new RadioSettingsStore(
				objectMapper,
				new RadioConfigProperties(tempDir.resolve("missing-llm-model-config.json").toString()));
		store.save(configured);
		ProviderHealthService service = new ProviderHealthService(
				new ProviderRegistry(store),
				new StreamEventService(),
				objectMapper);

		SettingsDtos.ProviderHealthPayload health = service.refreshHealth().get("llm");

		assertEquals("DEGRADED", health.status());
		assertEquals(false, health.metadata().get("selectedModelAvailable"));
		assertEquals("接続できましたが、指定した LLM モデル missing-model:latest が見つかりません。", health.message());
	}

	@Test
	void aceStepHealthIsDownWhenApiIsAliveButModelsAreNotInitialized() {
		httpServer.createContext("/uninitialized/health", new FixedResponseHandler(
				200,
				"""
						{"data":{
						  "status":"ok",
						  "models_initialized":false,
						  "llm_initialized":false,
						  "loaded_model":"acestep-v15-turbo",
						  "loaded_lm_model":null
						}}
						"""));
		httpServer.createContext("/uninitialized/v1/models", new FixedResponseHandler(
				200,
				"{\"object\":\"list\",\"data\":[]}"));
		httpServer.createContext("/uninitialized/v1/stats", new FixedResponseHandler(
				200,
				"{\"data\":{\"queue_size\":0,\"jobs\":{\"queued\":0,\"running\":0}}}"));
		SettingsDocument defaults = SettingsDocument.defaults();
		String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/uninitialized";
		SettingsDocument configured = new SettingsDocument(
				defaults.version(),
				defaults.schemaVersion(),
				defaults.updatedAt(),
				defaults.server(),
				defaults.paths(),
				defaults.playout(),
				defaults.cache(),
				defaults.programming(),
				new SettingsDocument.ProviderCatalog(
						defaults.providers().llm(),
						defaults.providers().tts(),
						new SettingsDocument.ProviderGroup(
								"ace-step",
								List.of(),
								Map.of("ace-step", new SettingsDocument.ProviderEndpoint(
										baseUrl,
										"/health",
										1_000,
										List.of("MUSIC_GEN", "ACE_STEP"),
										"ACE_STEP",
										null,
										"ace-ja-fast",
										SettingsDocument.MusicGenerationModelProfile.defaultAceStepProfiles())))),
				defaults.security(),
				defaults.features()).normalize();
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		RadioSettingsStore store = new RadioSettingsStore(
				objectMapper,
				new RadioConfigProperties(tempDir.resolve("uninitialized-ace-config.json").toString()));
		store.save(configured);
		ProviderHealthService service = new ProviderHealthService(
				new ProviderRegistry(store),
				new StreamEventService(),
				objectMapper);

		SettingsDtos.ProviderHealthPayload health = service.refreshHealth().get("musicGen");

		assertEquals("DOWN", health.status());
		assertEquals(false, health.metadata().get("modelsInitialized"));
		assertEquals(false, health.metadata().get("llmInitialized"));
		assertEquals(List.of(), health.metadata().get("models"));
		assertEquals(
				"ACE-Step の音楽モデルが初期化されていません。Provider 側の起動設定とモデル読込状態を確認してください。",
				health.message());
	}

	@Test
	void aceStepHealthAcceptsCurrentOpenAiCompatibleModelDisplayName() {
		httpServer.createContext("/current-ace/health", new FixedResponseHandler(
				200,
				"""
						{"data":{
						  "status":"ok",
						  "models_initialized":true,
						  "llm_initialized":true,
						  "loaded_model":"acestep-v15-turbo",
						  "loaded_lm_model":"acestep-5Hz-lm-0.6B"
						}}
						"""));
		httpServer.createContext("/current-ace/v1/models", new FixedResponseHandler(
				200,
				"""
						{"object":"list","data":[{
						  "id":"acestep/acestep-v15-turbo",
						  "name":"ACE-Step acestep-v15-turbo"
						}]}
						"""));
		httpServer.createContext("/current-ace/v1/stats", new FixedResponseHandler(
				200,
				"{\"data\":{\"queue_size\":0,\"jobs\":{\"queued\":0,\"running\":0}}}"));
		SettingsDocument defaults = SettingsDocument.defaults();
		String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/current-ace";
		SettingsDocument configured = new SettingsDocument(
				defaults.version(),
				defaults.schemaVersion(),
				defaults.updatedAt(),
				defaults.server(),
				defaults.paths(),
				defaults.playout(),
				defaults.cache(),
				defaults.programming(),
				new SettingsDocument.ProviderCatalog(
						defaults.providers().llm(),
						defaults.providers().tts(),
						new SettingsDocument.ProviderGroup(
								"ace-step",
								List.of(),
								Map.of("ace-step", new SettingsDocument.ProviderEndpoint(
										baseUrl,
										"/health",
										1_000,
										List.of("MUSIC_GEN", "ACE_STEP"),
										"ACE_STEP",
										null,
										"ace-ja-fast",
										SettingsDocument.MusicGenerationModelProfile.defaultAceStepProfiles())))),
				defaults.security(),
				defaults.features()).normalize();
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		RadioSettingsStore store = new RadioSettingsStore(
				objectMapper,
				new RadioConfigProperties(tempDir.resolve("current-ace-config.json").toString()));
		store.save(configured);
		ProviderHealthService service = new ProviderHealthService(
				new ProviderRegistry(store),
				new StreamEventService(),
				objectMapper);

		SettingsDtos.ProviderHealthPayload health = service.refreshHealth().get("musicGen");

		assertEquals("UP", health.status());
		assertEquals("接続成功", health.message());
		assertEquals(List.of("ACE-Step acestep-v15-turbo"), health.metadata().get("models"));
		assertEquals("acestep-v15-turbo", health.metadata().get("loadedModel"));
	}

	@Test
	void metadataOnlyChangeIsMeaningfulForProviderHealthEvents() throws Exception {
		Instant checkedAt = Instant.parse("2026-07-21T00:00:00Z");
		SettingsDtos.ProviderHealthPayload before = new SettingsDtos.ProviderHealthPayload(
				"tts", "irodori", "UP", checkedAt, 10L, "接続成功",
				List.of("TTS_GEN"), "http://127.0.0.1:8088",
				Map.of("upstreamChunkSseAvailable", false));
		SettingsDtos.ProviderHealthPayload after = new SettingsDtos.ProviderHealthPayload(
				"tts", "irodori", "UP", checkedAt.plusSeconds(1), 11L, "接続成功",
				List.of("TTS_GEN"), "http://127.0.0.1:8088",
				Map.of("upstreamChunkSseAvailable", true));
		var method = ProviderHealthService.class.getDeclaredMethod("hasMeaningfulChange", Map.class, Map.class);
		method.setAccessible(true);

		boolean meaningful = (boolean) method.invoke(providerHealthService, Map.of("tts", before), Map.of("tts", after));

		assertEquals(true, meaningful);
	}

	@Test
	void irodoriHealthUsesBearerTokenAndSeparatesUpstreamStreamingCapability() throws Exception {
		Path tokenFile = tempDir.resolve("irodori-token.txt");
		java.nio.file.Files.writeString(tokenFile, "health-secret");
		AtomicReference<String> healthAuthorization = new AtomicReference<>();
		AtomicReference<String> modelsAuthorization = new AtomicReference<>();
		httpServer.createContext("/irodori/health", exchange -> {
			healthAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			write(exchange, 200, "ok");
		});
		httpServer.createContext("/irodori/v1/models", exchange -> {
			modelsAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			write(exchange, 200, "{\"object\":\"list\",\"data\":[{\"id\":\"irodori-tts\"}]}");
		});
		String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/irodori";
		SettingsDocument.ProviderCatalog defaults = SettingsDocument.ProviderCatalog.defaults();
		SettingsDocument settings = SettingsDocument.defaults();
		settings = new SettingsDocument(
				settings.version(),
				settings.schemaVersion(),
				settings.updatedAt(),
				settings.server(),
				new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("music").toString()),
				settings.playout(),
				settings.cache(),
				settings.programming(),
				new SettingsDocument.ProviderCatalog(
						defaults.llm(),
						new SettingsDocument.ProviderGroup(
								"irodori",
								List.of(),
								Map.of("irodori", new SettingsDocument.ProviderEndpoint(
										baseUrl,
										"/health",
										1_000,
										List.of("TTS_GEN", "IRODORI_TTS", "LONG_TEXT_CHUNKING", "CHUNK_SSE_AVAILABLE"),
										"IRODORI_OPENAI_TTS",
										"file:" + tokenFile,
										"irodori-tts",
										Map.of()))),
						defaults.musicGen()),
				settings.security(),
				settings.features()).normalize();
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		RadioSettingsStore store = new RadioSettingsStore(objectMapper, new RadioConfigProperties(tempDir.resolve("irodori-config.json").toString()));
		store.save(settings);
		ProviderHealthService service = new ProviderHealthService(new ProviderRegistry(store), new StreamEventService(), objectMapper);

		SettingsDtos.ProviderHealthPayload health = service.refreshHealth().get("tts");

		assertEquals("UP", health.status());
		assertEquals("Bearer health-secret", healthAuthorization.get());
		assertEquals("Bearer health-secret", modelsAuthorization.get());
		assertEquals(true, health.metadata().get("upstreamChunkSseAvailable"));
		assertEquals(false, health.metadata().get("adapterStreamingEnabled"));
		assertEquals(List.of("irodori-tts"), health.metadata().get("models"));
	}

	@Test
	void missingProviderSecretReferenceIsReportedAsAuthenticationFailure() {
		String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
		SettingsDocument settings = SettingsDocument.defaults();
		SettingsDocument.ProviderCatalog defaults = settings.providers();
		SettingsDocument.ProviderEndpoint aceStep = new SettingsDocument.ProviderEndpoint(
				baseUrl,
				"/up",
				1_000,
				List.of("MUSIC_GEN", "ACE_STEP"),
				"ACE_STEP",
				"file:" + tempDir.resolve("missing-acestep-token"),
				"ace-ja-fast",
				SettingsDocument.MusicGenerationModelProfile.defaultAceStepProfiles());
		SettingsDocument configured = new SettingsDocument(
				settings.version(),
				settings.schemaVersion(),
				settings.updatedAt(),
				settings.server(),
				settings.paths(),
				settings.playout(),
				settings.cache(),
				settings.programming(),
				new SettingsDocument.ProviderCatalog(
						defaults.llm(),
						defaults.tts(),
						new SettingsDocument.ProviderGroup("ace-step", List.of(), Map.of("ace-step", aceStep))),
				settings.security(),
				settings.features()).normalize();
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		RadioSettingsStore store = new RadioSettingsStore(
				objectMapper,
				new RadioConfigProperties(tempDir.resolve("missing-secret-config.json").toString()));
		store.save(configured);
		ProviderHealthService service = new ProviderHealthService(
				new ProviderRegistry(store),
				new StreamEventService(),
				objectMapper);

		SettingsDtos.ProviderHealthPayload health = service.refreshHealth().get("musicGen");

		assertEquals("DOWN", health.status());
		assertEquals("PROVIDER_AUTH_FAILED", health.message());
	}

	private void write(HttpExchange exchange, int statusCode, String body) throws IOException {
		byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(statusCode, bytes.length);
		try (OutputStream outputStream = exchange.getResponseBody()) {
			outputStream.write(bytes);
		}
	}

	@SuppressWarnings("unchecked")
	private List<RadioEventRecord> replayAfter(String lastEventId) {
		try {
			var method = StreamEventService.class.getDeclaredMethod("replayAfter", String.class);
			method.setAccessible(true);
			return (List<RadioEventRecord>) method.invoke(streamEventService, lastEventId);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("StreamEventService.replayAfter の呼び出しに失敗しました。", exception);
		}
	}

	private static final class FixedResponseHandler implements HttpHandler {

		private final int statusCode;
		private final byte[] body;

		private FixedResponseHandler(int statusCode, String body) {
			this.statusCode = statusCode;
			this.body = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		}

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			exchange.sendResponseHeaders(statusCode, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		}
	}
}
