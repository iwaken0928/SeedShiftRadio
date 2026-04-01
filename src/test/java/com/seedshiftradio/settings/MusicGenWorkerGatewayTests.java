package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
				"2026-03",
				Instant.parse("2026-03-20T09:00:00Z"),
				SettingsDocument.ServerSettings.defaults(),
				new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("data").resolve("library").resolve("music").toString()),
				SettingsDocument.PlayoutSettings.defaults(),
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
