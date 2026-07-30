package com.seedshiftradio.settings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.domain.ProviderType;

/**
 * 1 GPU を共有する Ollama と ACE-Step の実行権を Server 内で直列化する。
 */
@Service
public class GpuExecutionCoordinator {

	private static final String ADAPTER_OLLAMA = "OLLAMA";

	private final RadioSettingsStore settingsStore;
	private final ProviderRegistry providerRegistry;
	private final MusicGenWorkerGateway musicGenWorkerGateway;
	private final ObjectMapper objectMapper;
	private final ReentrantLock executionLock = new ReentrantLock(true);
	private final AtomicInteger waitingJobs = new AtomicInteger();

	private volatile ActiveExecution activeExecution;
	private volatile Instant lastCompletedAt;
	private volatile String lastOutcome = "IDLE";

	public GpuExecutionCoordinator(
			RadioSettingsStore settingsStore,
			ProviderRegistry providerRegistry,
			MusicGenWorkerGateway musicGenWorkerGateway,
			ObjectMapper objectMapper) {
		this.settingsStore = settingsStore;
		this.providerRegistry = providerRegistry;
		this.musicGenWorkerGateway = musicGenWorkerGateway;
		this.objectMapper = objectMapper;
	}

	public <T> T execute(
			ExecutionOrigin origin,
			InferenceWorkload workload,
			String providerKey,
			Supplier<T> action) {
		SettingsDocument.JobExecutionSettings settings = settingsStore.load().features().jobExecution();
		SettingsDocument.JobExecutionPolicy policy = origin == ExecutionOrigin.MANUAL
				? settings.manual()
				: settings.automatic();
		if (!Boolean.TRUE.equals(settings.singleGpuMode())) {
			return InferenceExecutionContext.withPolicy(policy, action);
		}

		boolean acquired = false;
		waitingJobs.incrementAndGet();
		try {
			acquired = acquire(policy);
			if (!acquired) {
				throw failure(
						ProviderErrorCode.PROVIDER_TIMEOUT,
						"GPU resource group の実行権待機がタイムアウトしました。");
			}
		} finally {
			waitingJobs.decrementAndGet();
		}

		String executionId = UUID.randomUUID().toString();
		activeExecution = new ActiveExecution(
				executionId,
				settings.resourceGroup(),
				origin,
				workload,
				safeProviderKey(providerKey),
				"PREPARING",
				Instant.now());
		try {
			prepareProviders(workload, policy);
			activeExecution = activeExecution.withPhase("RUNNING");
			T result = InferenceExecutionContext.withPolicy(policy, action);
			lastOutcome = "SUCCEEDED";
			return result;
		} catch (RuntimeException exception) {
			lastOutcome = "FAILED";
			throw exception;
		} finally {
			lastCompletedAt = Instant.now();
			activeExecution = null;
			if (acquired) {
				executionLock.unlock();
			}
		}
	}

	public ExecutionStatus status() {
		SettingsDocument.JobExecutionSettings settings = settingsStore.load().features().jobExecution();
		ActiveExecution current = activeExecution;
		return new ExecutionStatus(
				current != null || Boolean.TRUE.equals(settings.singleGpuMode()),
				current == null ? settings.resourceGroup() : current.resourceGroup(),
				current == null ? "IDLE" : current.phase(),
				waitingJobs.get(),
				current == null ? null : current.executionId(),
				current == null ? null : current.origin().name(),
				current == null ? null : current.workload().name(),
				current == null ? null : current.providerKey(),
				current == null ? null : current.startedAt(),
				lastCompletedAt,
				lastOutcome,
				Boolean.TRUE.equals(settings.requireAceStepCpuOffload()));
	}

	private boolean acquire(SettingsDocument.JobExecutionPolicy policy) {
		try {
			if ("FAIL_FAST".equals(policy.waitStrategy())) {
				return executionLock.tryLock();
			}
			return executionLock.tryLock(policy.resourceWaitTimeoutSeconds(), TimeUnit.SECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw failure(ProviderErrorCode.PROVIDER_INTERRUPTED, "GPU resource group の実行権待機が中断されました。", exception);
		}
	}

	private void prepareProviders(
			InferenceWorkload workload,
			SettingsDocument.JobExecutionPolicy policy) {
		if (workload == InferenceWorkload.MUSIC && Boolean.TRUE.equals(policy.unloadOllamaBeforeMusic())) {
			unloadOllamaModels(policy);
		}
		if (workload == InferenceWorkload.LLM && Boolean.TRUE.equals(policy.waitForAceStepIdleBeforeLlm())) {
			waitForAceStepIdle(policy);
		}
	}

	private void unloadOllamaModels(SettingsDocument.JobExecutionPolicy policy) {
		for (ProviderRegistry.ResolvedProvider provider : providerRegistry.resolveChain(ProviderType.LLM)) {
			if (!ADAPTER_OLLAMA.equalsIgnoreCase(provider.adapter())) {
				continue;
			}
			String model = provider.defaultModelProfileId();
			if (model == null || model.isBlank()) {
				continue;
			}
			postOllamaUnload(provider, model, policy);
			waitForOllamaUnload(provider, model, policy);
		}
	}

	private void postOllamaUnload(
			ProviderRegistry.ResolvedProvider provider,
			String model,
			SettingsDocument.JobExecutionPolicy policy) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("model", model);
		payload.put("keep_alive", 0);
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(provider.baseUrl(), "/api/generate"))
				.timeout(requestTimeout(provider.timeoutMs(), policy.modelLoadTimeoutSeconds()))
				.header("Accept", "application/json")
				.header("Content-Type", "application/json");
		addAuthorization(builder, provider.apiKeyRef());
		HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(serialize(payload), StandardCharsets.UTF_8)).build();
		sendExpectSuccess(request, requestTimeout(provider.timeoutMs(), policy.modelLoadTimeoutSeconds()), "Ollama のアンロード");
	}

	private void waitForOllamaUnload(
			ProviderRegistry.ResolvedProvider provider,
			String model,
			SettingsDocument.JobExecutionPolicy policy) {
		Instant deadline = Instant.now().plusSeconds(policy.providerIdleTimeoutSeconds());
		while (true) {
			HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(provider.baseUrl(), "/api/ps"))
					.timeout(requestTimeout(provider.timeoutMs(), policy.providerIdleTimeoutSeconds()))
					.header("Accept", "application/json");
			addAuthorization(builder, provider.apiKeyRef());
			JsonNode root = parse(sendExpectSuccess(
					builder.GET().build(),
					requestTimeout(provider.timeoutMs(), policy.providerIdleTimeoutSeconds()),
					"Ollama のロード状態確認"));
			boolean loaded = false;
			for (JsonNode node : root.path("models")) {
				String loadedModel = node.path("model").asText(node.path("name").asText(""));
				if (model.equals(loadedModel)) {
					loaded = true;
					break;
				}
			}
			if (!loaded) {
				return;
			}
			if (Instant.now().isAfter(deadline)) {
				throw failure(ProviderErrorCode.PROVIDER_TIMEOUT, "Ollama モデルのアンロード待機がタイムアウトしました。");
			}
			sleep(policy.pollIntervalMillis());
		}
	}

	private void waitForAceStepIdle(SettingsDocument.JobExecutionPolicy policy) {
		Instant deadline = Instant.now().plusSeconds(policy.providerIdleTimeoutSeconds());
		for (MusicGenWorkerGateway.ResolvedMusicProvider provider : musicGenWorkerGateway.resolveProviders()) {
			if (!provider.isAceStep()) {
				continue;
			}
			while (true) {
				MusicGenWorkerGateway.RuntimeStats stats = musicGenWorkerGateway.stats(provider);
				Integer queued = stats.queuedJobs();
				Integer running = stats.runningJobs();
				if (queued != null && running != null && queued == 0 && running == 0) {
					break;
				}
				if (Instant.now().isAfter(deadline)) {
					throw failure(ProviderErrorCode.PROVIDER_TIMEOUT, "ACE-Step ジョブの完了待機がタイムアウトしました。");
				}
				sleep(policy.pollIntervalMillis());
			}
		}
	}

	private String sendExpectSuccess(HttpRequest request, Duration timeout, String operation) {
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw failure(
						ProviderErrorClassifier.fromHttpStatus(response.statusCode()),
						operation + "に失敗しました。HTTP status=" + response.statusCode());
			}
			return response.body() == null ? "" : response.body();
		} catch (java.net.http.HttpTimeoutException exception) {
			throw failure(ProviderErrorCode.PROVIDER_TIMEOUT, operation + "がタイムアウトしました。", exception);
		} catch (IOException exception) {
			throw failure(ProviderErrorCode.PROVIDER_UNREACHABLE, operation + "で provider に接続できません。", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw failure(ProviderErrorCode.PROVIDER_INTERRUPTED, operation + "が中断されました。", exception);
		}
	}

	private void addAuthorization(HttpRequest.Builder builder, String secretRef) {
		String secret = resolveSecret(secretRef);
		if (secret != null) {
			builder.header("Authorization", "Bearer " + secret);
		}
	}

	private String resolveSecret(String secretRef) {
		if (secretRef == null || secretRef.isBlank()) {
			return null;
		}
		if (secretRef.startsWith("env:")) {
			String value = System.getenv(secretRef.substring("env:".length()));
			if (value == null || value.isBlank()) {
				throw failure(ProviderErrorCode.PROVIDER_AUTH_FAILED, "provider の環境変数参照を解決できません。");
			}
			return value;
		}
		if (secretRef.startsWith("file:")) {
			try {
				String value = Files.readString(
						Path.of(secretRef.substring("file:".length())),
						StandardCharsets.UTF_8).trim();
				if (value.isBlank()) {
					throw failure(ProviderErrorCode.PROVIDER_AUTH_FAILED, "provider の file 参照が空です。");
				}
				return value;
			} catch (IOException exception) {
				throw failure(ProviderErrorCode.PROVIDER_AUTH_FAILED, "provider の file 参照を解決できません。", exception);
			}
		}
		throw failure(ProviderErrorCode.PROVIDER_AUTH_FAILED, "provider の秘密参照は env: または file: で指定してください。");
	}

	private URI endpoint(String baseUrl, String path) {
		try {
			String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
			return URI.create(normalized + path);
		} catch (RuntimeException exception) {
			throw failure(ProviderErrorCode.PROVIDER_BAD_RESPONSE, "provider endpoint が不正です。", exception);
		}
	}

	private Duration requestTimeout(int providerTimeoutMs, int policyTimeoutSeconds) {
		long policyMillis = Duration.ofSeconds(policyTimeoutSeconds).toMillis();
		return Duration.ofMillis(Math.max(100L, Math.min(Math.max(100, providerTimeoutMs), policyMillis)));
	}

	private JsonNode parse(String value) {
		try {
			return objectMapper.readTree(value);
		} catch (JsonProcessingException exception) {
			throw failure(ProviderErrorCode.PROVIDER_BAD_RESPONSE, "provider の状態応答 JSON が不正です。", exception);
		}
	}

	private String serialize(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw failure(ProviderErrorCode.PROVIDER_BAD_RESPONSE, "provider 制御 request の JSON 化に失敗しました。", exception);
		}
	}

	private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw failure(ProviderErrorCode.PROVIDER_INTERRUPTED, "provider の待機中に割り込みが発生しました。", exception);
		}
	}

	private String safeProviderKey(String providerKey) {
		return providerKey == null || providerKey.isBlank() ? "provider-chain" : providerKey;
	}

	private ProviderRuntimeException failure(ProviderErrorCode errorCode, String message) {
		return new ProviderRuntimeException(errorCode, message);
	}

	private ProviderRuntimeException failure(ProviderErrorCode errorCode, String message, Throwable cause) {
		return new ProviderRuntimeException(errorCode, message, cause);
	}

	public enum ExecutionOrigin {
		MANUAL,
		AUTOMATIC
	}

	public enum InferenceWorkload {
		LLM,
		MUSIC
	}

	public record ExecutionStatus(
			boolean singleGpuMode,
			String resourceGroup,
			String phase,
			int waitingJobs,
			String executionId,
			String origin,
			String workload,
			String providerKey,
			Instant startedAt,
			Instant lastCompletedAt,
			String lastOutcome,
			boolean aceStepCpuOffloadRequired) {
	}

	private record ActiveExecution(
			String executionId,
			String resourceGroup,
			ExecutionOrigin origin,
			InferenceWorkload workload,
			String providerKey,
			String phase,
			Instant startedAt) {

		ActiveExecution withPhase(String newPhase) {
			return new ActiveExecution(
					executionId,
					resourceGroup,
					origin,
					workload,
					providerKey,
					newPhase,
					startedAt);
		}
	}
}
