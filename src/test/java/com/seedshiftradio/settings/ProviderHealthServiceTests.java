package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
		httpServer.start();

		String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
		SettingsDocument settings = new SettingsDocument(
				1,
				1,
				Instant.parse("2026-03-20T09:00:00Z"),
				SettingsDocument.ServerSettings.defaults(),
				new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("data").resolve("library").resolve("music").toString()),
				SettingsDocument.PlayoutSettings.defaults(),
				SettingsDocument.ProgrammingSettings.defaults(),
				new SettingsDocument.ProviderCatalog(
						new SettingsDocument.ProviderGroup("ollama", Map.of("ollama", new SettingsDocument.ProviderEndpoint(baseUrl, "/up", 1_000, List.of("SCRIPT_GEN")))),
						new SettingsDocument.ProviderGroup("voicevox", Map.of("voicevox", new SettingsDocument.ProviderEndpoint(baseUrl, "/error", 1_000, List.of("TTS_GEN")))),
						new SettingsDocument.ProviderGroup("ace-step", Map.of("ace-step", new SettingsDocument.ProviderEndpoint("http://127.0.0.1:1", "/health", 1_000, List.of("MUSIC_GEN"))))),
				SettingsDocument.SecuritySettings.defaults(),
				SettingsDocument.FeatureSettings.defaults())
				.normalize();

		RadioSettingsStore store = new RadioSettingsStore(new ObjectMapper().findAndRegisterModules(), new RadioConfigProperties(tempDir.resolve("config.json").toString()));
		store.save(settings);
		streamEventService = new StreamEventService();
		providerHealthService = new ProviderHealthService(store, streamEventService);
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
		assertEquals("DEGRADED", response.get("tts").status());
		assertEquals("DOWN", response.get("musicGen").status());
		assertEquals(1, events.size());
		assertEquals("provider.health.changed", events.getFirst().eventType());
		assertEquals(response, events.getFirst().payload());
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
