package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.settings.GpuExecutionCoordinator.ExecutionOrigin;
import com.seedshiftradio.settings.GpuExecutionCoordinator.InferenceWorkload;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@ExtendWith(MockitoExtension.class)
class GpuExecutionCoordinatorTests {

	@Mock
	RadioSettingsStore settingsStore;

	@Mock
	ProviderRegistry providerRegistry;

	@Mock
	MusicGenWorkerGateway musicGenWorkerGateway;

	HttpServer httpServer;

	@AfterEach
	void tearDown() {
		if (httpServer != null) {
			httpServer.stop(0);
		}
	}

	@Test
	void musicExecutionUnloadsOllamaBeforeRunningAction() throws Exception {
		AtomicInteger unloadRequests = new AtomicInteger();
		httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		httpServer.createContext("/api/generate", exchange -> {
			assertEquals("POST", exchange.getRequestMethod());
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(body.contains("\"model\":\"gemma3:latest\""));
			assertTrue(body.contains("\"keep_alive\":0"));
			unloadRequests.incrementAndGet();
			respond(exchange, "{}");
		});
		httpServer.createContext("/api/ps", exchange -> respond(exchange, "{\"models\":[]}"));
		httpServer.start();

		ProviderRegistry.ResolvedProvider ollama = new ProviderRegistry.ResolvedProvider(
				ProviderType.LLM,
				"llm",
				"ollama",
				"http://127.0.0.1:" + httpServer.getAddress().getPort(),
				"/api/tags",
				2_000,
				List.of("SCRIPT_GEN"),
				"OLLAMA",
				null,
				"gemma3:latest",
				Map.of(),
				false);
		when(settingsStore.load()).thenReturn(settings());
		when(providerRegistry.resolveChain(ProviderType.LLM)).thenReturn(List.of(ollama));

		GpuExecutionCoordinator coordinator = coordinator();
		String result = coordinator.execute(
				ExecutionOrigin.MANUAL,
				InferenceWorkload.MUSIC,
				"ace-step",
				() -> {
					assertEquals("RUNNING", coordinator.status().phase());
					return "generated";
				});

		assertEquals("generated", result);
		assertEquals(1, unloadRequests.get());
		assertEquals("IDLE", coordinator.status().phase());
		assertEquals("SUCCEEDED", coordinator.status().lastOutcome());
	}

	@Test
	void llmExecutionWaitsUntilAceStepHasNoQueuedOrRunningJobs() {
		MusicGenWorkerGateway.ResolvedMusicProvider aceStep = new MusicGenWorkerGateway.ResolvedMusicProvider(
				"ace-step",
				"http://127.0.0.1:8001",
				2_000,
				List.of("MUSIC_GEN", "ACE_STEP"),
				"ACE_STEP",
				null,
				"ace-ja-fast",
				SettingsDocument.MusicGenerationModelProfile.defaultAceStepProfiles());
		when(settingsStore.load()).thenReturn(settings());
		when(musicGenWorkerGateway.resolveProviders()).thenReturn(List.of(aceStep));
		when(musicGenWorkerGateway.stats(aceStep))
				.thenReturn(new MusicGenWorkerGateway.RuntimeStats(1, 1, 2, 30.0))
				.thenReturn(new MusicGenWorkerGateway.RuntimeStats(0, 0, 0, 30.0));

		GpuExecutionCoordinator coordinator = coordinator();
		String result = coordinator.execute(
				ExecutionOrigin.AUTOMATIC,
				InferenceWorkload.LLM,
				"ollama",
				() -> "script");

		assertEquals("script", result);
		assertEquals("SUCCEEDED", coordinator.status().lastOutcome());
	}

	@Test
	void failFastPolicyRejectsSecondJobWhileGpuIsInUse() throws Exception {
		SettingsDocument.JobExecutionPolicy failFastPolicy = new SettingsDocument.JobExecutionPolicy(
				"FAIL_FAST",
				2,
				2,
				2,
				2,
				100,
				false,
				false);
		when(settingsStore.load()).thenReturn(settings(failFastPolicy));
		GpuExecutionCoordinator coordinator = coordinator();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<String> first = executor.submit(() -> coordinator.execute(
					ExecutionOrigin.AUTOMATIC,
					InferenceWorkload.MUSIC,
					"ace-step",
					() -> {
						started.countDown();
						await(release);
						return "first";
					}));
			assertTrue(started.await(2, TimeUnit.SECONDS));

			ProviderRuntimeException exception = org.junit.jupiter.api.Assertions.assertThrows(
					ProviderRuntimeException.class,
					() -> coordinator.execute(
							ExecutionOrigin.MANUAL,
							InferenceWorkload.LLM,
							"ollama",
							() -> "second"));
			assertEquals(ProviderErrorCode.PROVIDER_TIMEOUT, exception.providerErrorCode());

			release.countDown();
			assertEquals("first", first.get(2, TimeUnit.SECONDS));
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	private GpuExecutionCoordinator coordinator() {
		return new GpuExecutionCoordinator(
				settingsStore,
				providerRegistry,
				musicGenWorkerGateway,
				new ObjectMapper().findAndRegisterModules());
	}

	private SettingsDocument settings() {
		SettingsDocument.JobExecutionPolicy policy = new SettingsDocument.JobExecutionPolicy(
				"WAIT",
				2,
				2,
				2,
				2,
				1,
				true,
				true);
		return settings(policy);
	}

	private SettingsDocument settings(SettingsDocument.JobExecutionPolicy policy) {
		SettingsDocument defaults = SettingsDocument.defaults().normalize();
		return new SettingsDocument(
				defaults.version(),
				defaults.schemaVersion(),
				Instant.now(),
				defaults.server(),
				defaults.paths(),
				defaults.playout(),
				defaults.cache(),
				defaults.programming(),
				defaults.providers(),
				defaults.security(),
				new SettingsDocument.FeatureSettings(
						defaults.features().streaming(),
						new SettingsDocument.JobExecutionSettings(true, "gpu-0", true, policy, policy)));
	}

	private void await(CountDownLatch latch) {
		try {
			if (!latch.await(2, TimeUnit.SECONDS)) {
				throw new AssertionError("latch timeout");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("latch interrupted", exception);
		}
	}

	private void respond(HttpExchange exchange, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
