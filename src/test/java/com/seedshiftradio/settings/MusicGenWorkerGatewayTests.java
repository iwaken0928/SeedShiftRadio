package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

class MusicGenWorkerGatewayTests {

	@TempDir
	Path tempDir;

	HttpServer fallbackServer;
	MusicGenWorkerGateway gateway;

	@BeforeEach
	void setUp() throws IOException {
		fallbackServer = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		fallbackServer.createContext("/music/jobs", new SubmitHandler());
		fallbackServer.start();

		String fallbackBaseUrl = "http://127.0.0.1:" + fallbackServer.getAddress().getPort();
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
						SettingsDocument.ProviderCatalog.defaults().llm(),
						SettingsDocument.ProviderCatalog.defaults().tts(),
						new SettingsDocument.ProviderGroup(
								"ace-step-primary",
								List.of("ace-step-fallback"),
								Map.of(
										"ace-step-primary", new SettingsDocument.ProviderEndpoint("http://127.0.0.1:1", "/health", 1_000, List.of("MUSIC_GEN")),
										"ace-step-fallback", new SettingsDocument.ProviderEndpoint(fallbackBaseUrl, "/health", 1_000, List.of("MUSIC_GEN"))))),
				SettingsDocument.SecuritySettings.defaults(),
				SettingsDocument.FeatureSettings.defaults())
				.normalize();
		RadioSettingsStore store = new RadioSettingsStore(new ObjectMapper().findAndRegisterModules(), new RadioConfigProperties(tempDir.resolve("config.json").toString()));
		store.save(settings);
		gateway = new MusicGenWorkerGateway(new ProviderRegistry(store), new ObjectMapper().findAndRegisterModules());
	}

	@AfterEach
	void tearDown() {
		if (fallbackServer != null) {
			fallbackServer.stop(0);
		}
	}

	@Test
	void submitWithFallbackUsesSecondConfiguredProvider() {
		List<MusicGenWorkerGateway.ResolvedMusicProvider> providers = gateway.resolveProviders();

		MusicGenWorkerGateway.SubmittedMusicJob submitted = gateway.submitWithFallback(
				providers,
				new MusicGenWorkerGateway.MusicJobRequest(
						"req-001",
						"station-night",
						"BGM",
						"ambient",
						List.of("calm"),
						30,
						7));

		assertEquals("ace-step-fallback", submitted.provider().providerKey());
		assertEquals("job-fallback-001", submitted.jobId());
	}

	@Test
	void workerSubmitKeepsMusicGenerationRequestContract() throws IOException {
		AtomicReference<String> requestBody = new AtomicReference<>();
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/music/jobs", exchange -> {
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = "{\"jobId\":\"worker-job-001\",\"status\":\"QUEUED\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		});
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
			MusicGenWorkerGateway.ResolvedMusicProvider provider = new MusicGenWorkerGateway.ResolvedMusicProvider(
					"worker",
					baseUrl,
					5_000,
					List.of("MUSIC_GEN"),
					"MUSICGEN_WORKER",
					null,
					"ace-ja-fast",
					SettingsDocument.MusicGenerationModelProfile.defaultAceStepProfiles());

			MusicGenWorkerGateway.SubmittedMusicJob submitted = gateway.submit(provider, new MusicGenerationRequest(
					"req-worker-001",
					"station-night",
					"radio",
					"JAPANESE_SONG",
					"clear Japanese vocal, bright city pop",
					"[Verse]\n夜明けの窓辺で",
					"ja",
					45,
					128,
					"C major",
					"4",
					12345,
					"ace-ja-fast",
					"wav"));

			assertEquals("worker-job-001", submitted.jobId());
			JsonNode payload = new ObjectMapper().readTree(requestBody.get());
			assertEquals("req-worker-001", payload.path("requestId").asText());
			assertEquals("radio", payload.path("purpose").asText());
			assertEquals("clear Japanese vocal, bright city pop", payload.path("prompt").asText());
			assertEquals("[Verse]\n夜明けの窓辺で", payload.path("lyrics").asText());
			assertEquals("ja", payload.path("lyricsLanguage").asText());
			assertEquals(45, payload.path("durationSeconds").asInt());
			assertEquals(128, payload.path("bpm").asInt());
			assertEquals("C major", payload.path("keyScale").asText());
			assertEquals("ace-ja-fast", payload.path("modelProfileId").asText());
			assertEquals("wav", payload.path("outputFormat").asText());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void workerPollReadsAdditionalSafeMetadata() throws IOException {
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/music/jobs/job-001", exchange -> {
			byte[] body = """
					{
					  "jobId": "job-001",
					  "status": "SUCCEEDED",
					  "assetPath": "/tmp/worker.wav",
					  "durationSec": 30,
					  "providerFingerprint": "deterministic-worker:1.0",
					  "promptHash": "prompt-hash",
					  "lyricsHash": "lyrics-hash",
					  "message": "generated",
					  "model": "deterministic-sine",
					  "lmModel": "none",
					  "seed": "7"
					}
					""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		});
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
			MusicGenWorkerGateway.ResolvedMusicProvider provider = new MusicGenWorkerGateway.ResolvedMusicProvider(
					"worker",
					baseUrl,
					2_000,
					List.of("MUSIC_GEN"));

			MusicGenWorkerGateway.MusicJobStatus status = gateway.poll(provider, "job-001");

			assertEquals("SUCCEEDED", status.status());
			assertEquals("lyrics-hash", status.lyricsHash());
			assertEquals("deterministic-sine", status.model());
			assertEquals("none", status.lmModel());
			assertEquals("7", status.seed());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void envSecretRefMustResolveToNonBlankValue() {
		MusicGenWorkerGateway.ResolvedMusicProvider provider = new MusicGenWorkerGateway.ResolvedMusicProvider(
				"worker",
				"http://127.0.0.1:1",
				2_000,
				List.of("MUSIC_GEN"),
				"MUSICGEN_WORKER",
				"env:SEEDSHIFT_RADIO_MISSING_KEY_FOR_UNIT_TEST_57D4B5F7",
				null,
				Map.of());

		MusicGenWorkerException exception = assertThrows(MusicGenWorkerException.class, () -> gateway.submit(provider, new MusicGenerationRequest(
				"req-auth-001",
				"station-night",
				"radio",
				"JAPANESE_SONG",
				"prompt",
				"",
				"ja",
				30,
				null,
				"",
				"4",
				null,
				null,
				"wav")));

		assertEquals("PROVIDER_AUTH_FAILED", exception.errorCode());
	}

	@Test
	void aceStepSubmitMapsJapaneseLyricsAndProfile() throws IOException {
		AtomicReference<String> requestBody = new AtomicReference<>();
		AtomicReference<String> upgradeHeader = new AtomicReference<>();
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/release_task", exchange -> {
			upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = "{\"data\":{\"task_id\":\"ace-task-001\",\"status\":\"queued\"}}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		});
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
			MusicGenWorkerGateway.ResolvedMusicProvider provider = aceProvider(baseUrl);

			MusicGenWorkerGateway.SubmittedMusicJob submitted = gateway.submit(provider, new MusicGenerationRequest(
					"req-ja-001",
					"station-night",
					"radio",
					"JAPANESE_SONG",
					"clear Japanese vocal, bright city pop",
					"[Verse]\n夜明けの窓辺で\n[Chorus]\nまた走り出す",
					"ja",
					45,
					128,
					"C major",
					"4",
					12345,
					"ace-ja-fast",
					"wav"));

			assertEquals("ace-task-001", submitted.jobId());
			assertNull(upgradeHeader.get(), "ACE-Step POST で h2c upgrade を要求してはいけません。");
			JsonNode payload = new ObjectMapper().readTree(requestBody.get());
			assertEquals("ja", payload.path("vocal_language").asText());
			assertEquals(true, payload.path("thinking").asBoolean());
			assertEquals("acestep-v15-turbo", payload.path("model").asText());
			assertEquals("acestep-5Hz-lm-0.6B", payload.path("lm_model_path").asText());
			assertEquals(45, payload.path("audio_duration").asInt());
			assertEquals("wav", payload.path("audio_format").asText());
			assertEquals(128, payload.path("bpm").asInt());
			assertEquals("C major", payload.path("key_scale").asText());
			assertEquals(12345, payload.path("seed").asInt());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void aceStepPollParsesResultJsonStringAndDownloadsAudio() throws IOException {
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/query_result", exchange -> {
			String result = "[{\"file\":\"/v1/audio?path=out.wav\",\"metas\":{\"duration\":45},\"dit_model\":\"acestep-v15-turbo\",\"lm_model\":\"acestep-5Hz-lm-0.6B\",\"prompt\":\"secret prompt\",\"lyrics\":\"secret lyrics\",\"seed_value\":\"12345\",\"audio_format\":\"wav\"}]";
			String escaped = result.replace("\\", "\\\\").replace("\"", "\\\"");
			byte[] body = ("{\"data\":[{\"status\":1,\"result\":\"" + escaped + "\"}]}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		});
		server.createContext("/v1/audio", exchange -> {
			byte[] body = "RIFF-test-audio".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "audio/wav");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		});
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
			MusicGenWorkerGateway.MusicJobStatus status = gateway.poll(aceProvider(baseUrl), "ace-task-001");

			assertEquals("SUCCEEDED", status.status());
			assertEquals(45, status.durationSec());
			assertEquals("acestep-v15-turbo", status.model());
			assertEquals("acestep-5Hz-lm-0.6B", status.lmModel());
			assertEquals("12345", status.seed());
			assertTrue(Path.of(status.assetPath()).toFile().exists());
			assertTrue(status.providerFingerprint().contains("acestep-v15-turbo"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void aceStepPollParsesResultJsonObjectAndDownloadsAudio() throws IOException {
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/query_result", exchange -> {
			byte[] body = """
					{
					  "data": [
					    {
					      "status": 1,
					      "result": {
					        "file": "/v1/audio?path=out.wav",
					        "metas": {"duration": 30},
					        "dit_model": "acestep-v15-sft",
					        "lm_model": "acestep-5Hz-lm-1.7B",
					        "prompt": "secret prompt",
					        "lyrics": "secret lyrics",
					        "seed_value": "24680",
					        "audio_format": "wav"
					      }
					    }
					  ]
					}
					""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		});
		server.createContext("/v1/audio", exchange -> {
			byte[] body = "RIFF-test-audio".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "audio/wav");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		});
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
			MusicGenWorkerGateway.MusicJobStatus status = gateway.poll(aceProvider(baseUrl), "ace-task-002");

			assertEquals("SUCCEEDED", status.status());
			assertEquals(30, status.durationSec());
			assertEquals("acestep-v15-sft", status.model());
			assertEquals("acestep-5Hz-lm-1.7B", status.lmModel());
			assertEquals("24680", status.seed());
			assertTrue(Path.of(status.assetPath()).toFile().exists());
			assertTrue(status.providerFingerprint().contains("acestep-v15-sft"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void awaitCompletionPropagatesWorkerFailureContract() throws IOException {
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/music/jobs/job-001", exchange -> {
			byte[] body = """
					{
					  "jobId": "job-001",
					  "status": "FAILED",
					  "assetPath": null,
					  "durationSec": null,
					  "providerFingerprint": "failing-worker:1.0",
					  "promptHash": "prompt-hash",
					  "lyricsHash": "lyrics-hash",
					  "errorCode": "PROVIDER_RESOURCE_EXHAUSTED",
					  "message": "gpu busy"
					}
					""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		});
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
			MusicGenWorkerGateway.ResolvedMusicProvider provider = new MusicGenWorkerGateway.ResolvedMusicProvider(
					"worker",
					baseUrl,
					2_000,
					List.of("MUSIC_GEN"));

			MusicGenWorkerException exception = assertThrows(
					MusicGenWorkerException.class,
					() -> gateway.awaitCompletion(provider, "job-001"));

			assertEquals("PROVIDER_RESOURCE_EXHAUSTED", exception.errorCode());
			assertEquals("gpu busy", exception.getMessage());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void aceStepModelCatalogAcceptsOpenAiCompatibleDataArray() throws IOException {
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/models", exchange -> {
			byte[] body = """
					{
					  "object": "list",
					  "data": [
					    {"id": "acestep-v15-turbo", "object": "model"},
					    {"id": "acestep-v15-sft", "object": "model"}
					  ]
					}
					""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		});
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

			MusicGenWorkerGateway.ModelCatalog catalog = gateway.listModels(aceProvider(baseUrl));

			assertEquals(
					List.of("acestep-v15-turbo", "acestep-v15-sft"),
					catalog.models().stream().map(MusicGenWorkerGateway.ModelInfo::name).toList());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void aceStepModelInitializationMapsProfileAndReadsLoadedModels() throws IOException {
		AtomicReference<String> requestBody = new AtomicReference<>();
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/init", exchange -> {
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = """
					{
					  "data": {
					    "message": "Model initialization completed",
					    "slot": 2,
					    "loaded_model": "acestep-v15-xl-turbo",
					    "loaded_lm_model": "acestep-5Hz-lm-1.7B",
					    "models": [
					      {"name": "acestep-v15-turbo", "is_loaded": true},
					      {"name": "acestep-v15-xl-turbo", "is_loaded": true}
					    ],
					    "lm_models": ["acestep-5Hz-lm-1.7B"],
					    "llm_initialized": true
					  }
					}
					""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		});
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
			SettingsDocument.MusicGenerationModelProfile profile =
					SettingsDocument.MusicGenerationModelProfile.defaultAceStepProfiles().get("ace-ja-xl-fast");

			MusicGenWorkerGateway.ModelInitializationResult result =
					gateway.initializeModel(aceProvider(baseUrl), profile, 2);

			JsonNode payload = new ObjectMapper().readTree(requestBody.get());
			assertEquals("acestep-v15-xl-turbo", payload.path("model").asText());
			assertEquals(2, payload.path("slot").asInt());
			assertTrue(payload.path("init_llm").asBoolean());
			assertEquals("acestep-5Hz-lm-1.7B", payload.path("lm_model_path").asText());
			assertEquals("acestep-v15-xl-turbo", result.loadedModel());
			assertEquals(List.of("acestep-v15-turbo", "acestep-v15-xl-turbo"), result.models());
			assertEquals(List.of("acestep-5Hz-lm-1.7B"), result.lmModels());
			assertTrue(result.llmInitialized());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void awaitCompletionNormalizesUnknownWorkerErrorCode() throws IOException {
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/music/jobs/job-unknown", exchange -> {
			byte[] body = """
					{
					  "jobId": "job-unknown",
					  "status": "FAILED",
					  "errorCode": "WORKER_PRIVATE_ERROR",
					  "message": "worker detail"
					}
					""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		});
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
			MusicGenWorkerGateway.ResolvedMusicProvider provider = new MusicGenWorkerGateway.ResolvedMusicProvider(
					"worker",
					baseUrl,
					2_000,
					List.of("MUSIC_GEN"));

			MusicGenWorkerException exception = assertThrows(
					MusicGenWorkerException.class,
					() -> gateway.awaitCompletion(provider, "job-unknown"));

			assertEquals("PROVIDER_BAD_RESPONSE", exception.errorCode());
		} finally {
			server.stop(0);
		}
	}

	private MusicGenWorkerGateway.ResolvedMusicProvider aceProvider(String baseUrl) {
		return new MusicGenWorkerGateway.ResolvedMusicProvider(
				"ace-step",
				baseUrl,
				2_000,
				List.of("MUSIC_GEN", "ACE_STEP", "JAPANESE_LYRICS"),
				"ACE_STEP",
				null,
				"ace-ja-fast",
				SettingsDocument.MusicGenerationModelProfile.defaultAceStepProfiles());
	}

	private static final class SubmitHandler implements HttpHandler {

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			byte[] body = "{\"jobId\":\"job-fallback-001\",\"status\":\"QUEUED\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		}
	}
}
