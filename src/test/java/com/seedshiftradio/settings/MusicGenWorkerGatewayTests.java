package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
	void aceStepSubmitMapsJapaneseLyricsAndProfile() throws IOException {
		AtomicReference<String> requestBody = new AtomicReference<>();
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/release_task", exchange -> {
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
