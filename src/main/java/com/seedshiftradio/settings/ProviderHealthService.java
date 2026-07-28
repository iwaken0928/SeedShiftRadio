package com.seedshiftradio.settings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.stream.StreamEventService;

@Service
public class ProviderHealthService {

	private final ProviderRegistry providerRegistry;
	private final StreamEventService streamEventService;
	private final ObjectMapper objectMapper;

	private volatile Map<String, SettingsDtos.ProviderHealthPayload> latestSnapshot = Map.of();

	public ProviderHealthService(
			ProviderRegistry providerRegistry,
			StreamEventService streamEventService,
			ObjectMapper objectMapper) {
		this.providerRegistry = providerRegistry;
		this.streamEventService = streamEventService;
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
	}

	public synchronized Map<String, SettingsDtos.ProviderHealthPayload> refreshHealth() {
		Map<String, SettingsDtos.ProviderHealthPayload> current = probeAll();
		if (latestSnapshot.isEmpty() || hasMeaningfulChange(latestSnapshot, current)) {
			streamEventService.publish("provider.health.changed", current);
		}
		latestSnapshot = Map.copyOf(current);
		return latestSnapshot;
	}

	public Map<String, SettingsDtos.ProviderHealthPayload> getLatestOrProbe() {
		Map<String, SettingsDtos.ProviderHealthPayload> snapshot = latestSnapshot;
		return snapshot.isEmpty() ? refreshHealth() : snapshot;
	}

	private Map<String, SettingsDtos.ProviderHealthPayload> probeAll() {
		Map<String, SettingsDtos.ProviderHealthPayload> result = new LinkedHashMap<>();
		result.put("llm", probe(ProviderType.LLM));
		result.put("tts", probe(ProviderType.TTS));
		result.put("musicGen", probe(ProviderType.MUSIC));
		return result;
	}

	private SettingsDtos.ProviderHealthPayload probe(ProviderType providerType) {
		List<ProbeResult> attempts = providerRegistry.resolveChain(providerType).stream()
				.map(provider -> probeProvider(providerType, provider))
				.toList();
		String providerTypeKey = providerRegistry.providerGroupKey(providerType);
		if (attempts.isEmpty()) {
			return down(providerTypeKey, null, null, "Provider が未設定です。", Instant.now(), 0L, List.of());
		}

		ProbeResult primary = attempts.getFirst();
		if ("UP".equals(primary.payload().status())) {
			return primary.payload();
		}

		ProbeResult fallbackUp = attempts.stream()
				.skip(1)
				.filter(result -> "UP".equals(result.payload().status()))
				.findFirst()
				.orElse(null);
		if (fallbackUp != null) {
			return new SettingsDtos.ProviderHealthPayload(
					providerTypeKey,
					fallbackUp.payload().providerKey(),
					"DEGRADED",
					fallbackUp.payload().lastCheckedAt(),
					fallbackUp.payload().responseTimeMs(),
					"defaultProvider " + primary.provider().providerKey() + " が " + primary.payload().status()
							+ " のため fallback " + fallbackUp.provider().providerKey() + " を使用します。",
					fallbackUp.payload().capabilities(),
					fallbackUp.payload().baseUrl(),
					fallbackUp.payload().metadata());
		}

		ProbeResult degraded = attempts.stream()
				.filter(result -> "DEGRADED".equals(result.payload().status()))
				.findFirst()
				.orElse(null);
		if (degraded != null) {
			String message = degraded == primary
					? degraded.payload().message()
					: "defaultProvider " + primary.provider().providerKey()
							+ " が利用できず、fallback " + degraded.provider().providerKey() + " も劣化状態です。 " + degraded.payload().message();
			return new SettingsDtos.ProviderHealthPayload(
					providerTypeKey,
					degraded.payload().providerKey(),
					"DEGRADED",
					degraded.payload().lastCheckedAt(),
					degraded.payload().responseTimeMs(),
					message,
					degraded.payload().capabilities(),
					degraded.payload().baseUrl(),
					degraded.payload().metadata());
		}

		ProbeResult lastFailure = attempts.getLast();
		String message = attempts.size() == 1
				? lastFailure.payload().message()
				: "defaultProvider " + primary.provider().providerKey()
						+ " から fallback を試行しましたが利用できません。 " + lastFailure.payload().message();
		return new SettingsDtos.ProviderHealthPayload(
				providerTypeKey,
				lastFailure.payload().providerKey(),
				"DOWN",
				lastFailure.payload().lastCheckedAt(),
				lastFailure.payload().responseTimeMs(),
				message,
				lastFailure.payload().capabilities(),
				lastFailure.payload().baseUrl(),
				lastFailure.payload().metadata());
	}

	private ProbeResult probeProvider(ProviderType providerType, ProviderRegistry.ResolvedProvider provider) {
		Instant checkedAt = Instant.now();
		long startedAt = System.nanoTime();
		try {
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofMillis(provider.timeoutMs()))
					.build();
			HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(provider.baseUrl() + provider.healthPath()))
					.GET()
					.timeout(Duration.ofMillis(provider.timeoutMs()));
			String apiKey = resolveSecret(provider.apiKeyRef());
			if (apiKey != null && !apiKey.isBlank()) {
				requestBuilder.header("Authorization", "Bearer " + apiKey);
			}
			HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
			long responseTimeMs = elapsedMillis(startedAt);
			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				JsonNode healthBody = parseJson(response.body());
				Map<String, Object> metadata = enrichProviderMetadata(providerType, provider, healthBody);
				boolean selectedModelUnavailable = providerType == ProviderType.LLM
						&& Boolean.FALSE.equals(metadata.get("selectedModelAvailable"));
				String readinessFailure = providerReadinessFailure(providerType, provider, metadata);
				return new ProbeResult(provider, new SettingsDtos.ProviderHealthPayload(
						provider.providerGroupKey(),
						provider.providerKey(),
						readinessFailure != null ? "DOWN" : selectedModelUnavailable ? "DEGRADED" : "UP",
						checkedAt,
						responseTimeMs,
						readinessFailure != null
								? readinessFailure
								: selectedModelUnavailable
								? "接続できましたが、指定した LLM モデル " + provider.defaultModelProfileId() + " が見つかりません。"
								: "接続成功",
						provider.capabilities(),
						provider.baseUrl(),
						metadata));
			}
			return new ProbeResult(provider, new SettingsDtos.ProviderHealthPayload(
					provider.providerGroupKey(),
					provider.providerKey(),
					"DEGRADED",
					checkedAt,
					responseTimeMs,
					"HTTP " + response.statusCode(),
					provider.capabilities(),
					provider.baseUrl(),
					enrichProviderMetadata(providerType, provider, objectMapper.createObjectNode())));
		} catch (ProviderSecretException exception) {
			return new ProbeResult(provider, down(provider.providerGroupKey(), provider.providerKey(), provider.baseUrl(), "PROVIDER_AUTH_FAILED", checkedAt, elapsedMillis(startedAt), provider.capabilities()));
		} catch (IllegalArgumentException exception) {
			return new ProbeResult(provider, down(provider.providerGroupKey(), provider.providerKey(), provider.baseUrl(), "無効な URL です。", checkedAt, elapsedMillis(startedAt), provider.capabilities()));
		} catch (HttpTimeoutException exception) {
			return new ProbeResult(provider, down(provider.providerGroupKey(), provider.providerKey(), provider.baseUrl(), "PROVIDER_TIMEOUT", checkedAt, elapsedMillis(startedAt), provider.capabilities()));
		} catch (IOException exception) {
			return new ProbeResult(provider, down(provider.providerGroupKey(), provider.providerKey(), provider.baseUrl(), "PROVIDER_UNREACHABLE", checkedAt, elapsedMillis(startedAt), provider.capabilities()));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return new ProbeResult(provider, down(provider.providerGroupKey(), provider.providerKey(), provider.baseUrl(), "PROVIDER_INTERRUPTED", checkedAt, elapsedMillis(startedAt), provider.capabilities()));
		}
	}

	private Map<String, Object> enrichProviderMetadata(
			ProviderType providerType,
			ProviderRegistry.ResolvedProvider provider,
			JsonNode healthBody) {
		if (providerType == ProviderType.LLM) {
			return enrichLlmProviderMetadata(provider);
		}
		if (providerType == ProviderType.TTS) {
			return enrichTtsProviderMetadata(provider);
		}
		if (providerType != ProviderType.MUSIC) {
			return Map.of();
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("adapter", musicAdapter(provider));
		if (provider.defaultModelProfileId() != null && !provider.defaultModelProfileId().isBlank()) {
			metadata.put("defaultModelProfileId", provider.defaultModelProfileId());
		}
		if (provider.modelProfiles() != null && !provider.modelProfiles().isEmpty()) {
			metadata.put("modelProfileIds", List.copyOf(provider.modelProfiles().keySet()));
		}
		if (!"ACE_STEP".equals(musicAdapter(provider)) && !provider.capabilities().contains("ACE_STEP")) {
			return metadata;
		}
		readAceStepReadiness(provider, healthBody, metadata);
		readAceStepStats(provider, metadata);
		readAceStepModels(provider, metadata);
		return metadata;
	}

	private void readAceStepReadiness(
			ProviderRegistry.ResolvedProvider provider,
			JsonNode healthBody,
			Map<String, Object> metadata) {
		JsonNode data = healthBody.path("data");
		if (!data.isObject()) {
			data = healthBody;
		}
		putBooleanIfPresent(metadata, "modelsInitialized", data.path("models_initialized"));
		putBooleanIfPresent(metadata, "llmInitialized", data.path("llm_initialized"));
		putTextIfPresent(metadata, "loadedModel", data.path("loaded_model"));
		putTextIfPresent(metadata, "loadedLmModel", data.path("loaded_lm_model"));

		SettingsDocument.MusicGenerationModelProfile profile = selectedMusicProfile(provider);
		if (profile != null) {
			metadata.put("selectedModel", profile.model());
			metadata.put("selectedLmModel", profile.lmModel());
			metadata.put("thinkingEnabled", profile.thinking());
		}
	}

	private String providerReadinessFailure(
			ProviderType providerType,
			ProviderRegistry.ResolvedProvider provider,
			Map<String, Object> metadata) {
		if (providerType != ProviderType.MUSIC
				|| (!"ACE_STEP".equals(musicAdapter(provider)) && !provider.capabilities().contains("ACE_STEP"))) {
			return null;
		}
		if (Boolean.FALSE.equals(metadata.get("modelsInitialized"))) {
			return "ACE-Step の音楽モデルが初期化されていません。Provider 側の起動設定とモデル読込状態を確認してください。";
		}
		SettingsDocument.MusicGenerationModelProfile profile = selectedMusicProfile(provider);
		if (profile != null
				&& Boolean.TRUE.equals(profile.thinking())
				&& Boolean.FALSE.equals(metadata.get("llmInitialized"))) {
			return "ACE-Step の 5Hz LM が初期化されていません。thinking を使う生成プロファイルには LLM の初期化が必要です。";
		}
		Object modelsValue = metadata.get("models");
		if (profile != null
				&& modelsValue instanceof List<?> models
				&& !models.isEmpty()
				&& models.stream().noneMatch(model -> aceStepModelMatches(model, profile.model()))) {
			return "ACE-Step に生成プロファイルのモデル " + profile.model() + " が読み込まれていません。";
		}
		return null;
	}

	private boolean aceStepModelMatches(Object catalogModel, String selectedModel) {
		if (!(catalogModel instanceof String model) || selectedModel == null || selectedModel.isBlank()) {
			return false;
		}
		return model.equals(selectedModel)
				|| model.endsWith("/" + selectedModel)
				|| model.endsWith(" " + selectedModel);
	}

	private SettingsDocument.MusicGenerationModelProfile selectedMusicProfile(
			ProviderRegistry.ResolvedProvider provider) {
		if (provider.modelProfiles() == null || provider.modelProfiles().isEmpty()) {
			return null;
		}
		SettingsDocument.MusicGenerationModelProfile profile =
				provider.modelProfiles().get(provider.defaultModelProfileId());
		if (profile == null) {
			profile = provider.modelProfiles().values().stream().findFirst().orElse(null);
		}
		return profile == null ? null : profile.normalize();
	}

	private Map<String, Object> enrichTtsProviderMetadata(ProviderRegistry.ResolvedProvider provider) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		String adapter = ttsAdapter(provider);
		metadata.put("adapter", adapter);
		metadata.put("streamingSupported", false);
		if ("VOICEVOX".equals(adapter)) {
			metadata.put("responseFormat", "wav");
			return metadata;
		}
		if (!"IRODORI_OPENAI_TTS".equals(adapter)) {
			return metadata;
		}
		metadata.put("model", provider.defaultModelProfileId() == null || provider.defaultModelProfileId().isBlank()
				? "irodori-tts"
				: provider.defaultModelProfileId());
		metadata.put("responseFormat", "wav");
		metadata.put("chunkingEnabled", provider.capabilities().contains("LONG_TEXT_CHUNKING"));
		metadata.put("upstreamChunkSseAvailable", provider.capabilities().contains("CHUNK_SSE_AVAILABLE"));
		metadata.put("adapterStreamingEnabled", false);
		metadata.put("voiceRefStatus", "VOICE_PROFILE_REQUIRED");
		readIrodoriModels(provider, metadata);
		return metadata;
	}

	private Map<String, Object> enrichLlmProviderMetadata(ProviderRegistry.ResolvedProvider provider) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		String adapter = provider.adapter() == null || provider.adapter().isBlank()
				? "UNKNOWN"
				: provider.adapter();
		metadata.put("adapter", adapter);
		if (provider.defaultModelProfileId() != null && !provider.defaultModelProfileId().isBlank()) {
			metadata.put("selectedModel", provider.defaultModelProfileId());
		}
		try {
			JsonNode response = sendJsonProbe(provider, "OLLAMA".equals(adapter) ? "/api/tags" : "/v1/models");
			List<String> models = new ArrayList<>();
			JsonNode modelNodes = "OLLAMA".equals(adapter) ? response.path("models") : response.path("data");
			if (modelNodes.isArray()) {
				for (JsonNode modelNode : modelNodes) {
					String model = textOrNull(modelNode.path("name"));
					if (model == null) {
						model = textOrNull(modelNode.path("model"));
					}
					if (model == null) {
						model = textOrNull(modelNode.path("id"));
					}
					if (model != null && !model.isBlank()) {
						models.add(model);
					}
				}
			}
			metadata.put("models", List.copyOf(models));
			if (provider.defaultModelProfileId() != null && !provider.defaultModelProfileId().isBlank()) {
				metadata.put("selectedModelAvailable", models.contains(provider.defaultModelProfileId()));
			}
		} catch (RuntimeException exception) {
			metadata.put("modelsStatus", "UNAVAILABLE");
		}
		return metadata;
	}

	private void readIrodoriModels(ProviderRegistry.ResolvedProvider provider, Map<String, Object> metadata) {
		try {
			JsonNode data = sendJsonProbe(provider, "/v1/models");
			List<String> models = new ArrayList<>();
			JsonNode modelNodes = data.path("data");
			if (modelNodes.isArray()) {
				for (JsonNode modelNode : modelNodes) {
					String id = textOrNull(modelNode.path("id"));
					if (id != null && !id.isBlank()) {
						models.add(id);
					}
				}
			}
			if (!models.isEmpty()) {
				metadata.put("models", List.copyOf(models));
			}
		} catch (RuntimeException exception) {
			metadata.put("modelsStatus", "UNAVAILABLE");
		}
	}

	private void readAceStepStats(ProviderRegistry.ResolvedProvider provider, Map<String, Object> metadata) {
		try {
			JsonNode data = sendJsonProbe(provider, "/v1/stats").path("data");
			JsonNode jobs = data.path("jobs");
			putIfPresent(metadata, "queuedJobs", jobs.path("queued"));
			putIfPresent(metadata, "runningJobs", jobs.path("running"));
			putIfPresent(metadata, "queueSize", data.path("queue_size"));
			putIfPresent(metadata, "averageJobSeconds", data.path("avg_job_seconds"));
		} catch (RuntimeException exception) {
			metadata.put("statsStatus", "UNAVAILABLE");
		}
	}

	private void readAceStepModels(ProviderRegistry.ResolvedProvider provider, Map<String, Object> metadata) {
		try {
			JsonNode data = sendJsonProbe(provider, "/v1/models").path("data");
			String defaultModel = data.isObject() ? textOrNull(data.path("default_model")) : null;
			if (defaultModel != null) {
				metadata.put("defaultModel", defaultModel);
			}
			List<String> models = new ArrayList<>();
			JsonNode modelNodes = data.isArray() ? data : data.path("models");
			if (modelNodes.isArray()) {
				for (JsonNode modelNode : modelNodes) {
					String name = textOrNull(modelNode.path("name"));
					if (name == null) {
						name = textOrNull(modelNode.path("id"));
					}
					if (name == null) {
						name = textOrNull(modelNode.path("model"));
					}
					if (name != null && !name.isBlank()) {
						models.add(name);
					}
				}
			}
			metadata.put("models", List.copyOf(models));
		} catch (RuntimeException exception) {
			metadata.put("modelsStatus", "UNAVAILABLE");
		}
	}

	private JsonNode parseJson(String body) {
		if (body == null || body.isBlank()) {
			return objectMapper.createObjectNode();
		}
		try {
			return objectMapper.readTree(body);
		} catch (IOException exception) {
			return objectMapper.createObjectNode();
		}
	}

	private JsonNode sendJsonProbe(ProviderRegistry.ResolvedProvider provider, String path) {
		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(provider.baseUrl() + path))
					.GET()
					.timeout(Duration.ofMillis(provider.timeoutMs()))
					.header("Accept", "application/json");
			String apiKey = resolveSecret(provider.apiKeyRef());
			if (apiKey != null && !apiKey.isBlank()) {
				builder.header("Authorization", "Bearer " + apiKey);
			}
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofMillis(provider.timeoutMs()))
					.build();
			HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IllegalStateException("provider probe failed");
			}
			return objectMapper.readTree(response.body());
		} catch (IOException exception) {
			throw new IllegalStateException("provider probe failed", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("provider probe interrupted", exception);
		}
	}

	private String musicAdapter(ProviderRegistry.ResolvedProvider provider) {
		return provider.adapter() == null || provider.adapter().isBlank() ? "MUSICGEN_WORKER" : provider.adapter();
	}

	private String ttsAdapter(ProviderRegistry.ResolvedProvider provider) {
		if ("VOICEVOX".equals(provider.adapter()) || "IRODORI_OPENAI_TTS".equals(provider.adapter())) {
			return provider.adapter();
		}
		if (provider.capabilities().contains("IRODORI_TTS") || provider.capabilities().contains("OPENAI_AUDIO_SPEECH")) {
			return "IRODORI_OPENAI_TTS";
		}
		if (provider.capabilities().contains("VOICEVOX") || provider.providerKey().toLowerCase(java.util.Locale.ROOT).contains("voicevox")) {
			return "VOICEVOX";
		}
		return "UNKNOWN";
	}

	private String resolveSecret(String secretRef) {
		if (secretRef == null || secretRef.isBlank()) {
			return null;
		}
		if (secretRef.startsWith("env:")) {
			String value = System.getenv(secretRef.substring("env:".length()));
			if (value == null || value.isBlank()) {
				throw new ProviderSecretException();
			}
			return value;
		}
		if (secretRef.startsWith("file:")) {
			try {
				String value = java.nio.file.Files.readString(java.nio.file.Path.of(secretRef.substring("file:".length()))).trim();
				if (value.isBlank()) {
					throw new ProviderSecretException();
				}
				return value;
			} catch (IOException exception) {
				throw new ProviderSecretException();
			}
		}
		throw new ProviderSecretException();
	}

	private static final class ProviderSecretException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	private void putIfPresent(Map<String, Object> metadata, String key, JsonNode value) {
		if (value == null || value.isMissingNode() || value.isNull()) {
			return;
		}
		if (value.isNumber()) {
			metadata.put(key, value.numberValue());
		} else {
			metadata.put(key, value.asText());
		}
	}

	private void putBooleanIfPresent(Map<String, Object> metadata, String key, JsonNode value) {
		if (value != null && !value.isMissingNode() && !value.isNull() && value.isBoolean()) {
			metadata.put(key, value.asBoolean());
		}
	}

	private void putTextIfPresent(Map<String, Object> metadata, String key, JsonNode value) {
		String text = textOrNull(value);
		if (text != null && !text.isBlank()) {
			metadata.put(key, text);
		}
	}

	private String textOrNull(JsonNode node) {
		return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
	}

	private boolean hasMeaningfulChange(
			Map<String, SettingsDtos.ProviderHealthPayload> previous,
			Map<String, SettingsDtos.ProviderHealthPayload> current) {
		if (!previous.keySet().equals(current.keySet())) {
			return true;
		}
		for (String key : previous.keySet()) {
			SettingsDtos.ProviderHealthPayload before = previous.get(key);
			SettingsDtos.ProviderHealthPayload after = current.get(key);
			if (!Objects.equals(before.status(), after.status())
					|| !Objects.equals(before.providerKey(), after.providerKey())
					|| !Objects.equals(before.message(), after.message())
					|| !Objects.equals(before.baseUrl(), after.baseUrl())
					|| !Objects.equals(before.capabilities(), after.capabilities())
					|| !Objects.equals(before.metadata(), after.metadata())) {
				return true;
			}
		}
		return false;
	}

	private SettingsDtos.ProviderHealthPayload down(
			String providerType,
			String providerKey,
			String baseUrl,
			String message,
			Instant checkedAt,
			long responseTimeMs,
			List<String> capabilities) {
		return new SettingsDtos.ProviderHealthPayload(
				providerType,
				providerKey,
				"DOWN",
				checkedAt,
				responseTimeMs,
				message,
				capabilities == null ? List.of() : capabilities,
				baseUrl,
				Map.of());
	}

	private long elapsedMillis(long startedAt) {
		return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
	}

	private record ProbeResult(ProviderRegistry.ResolvedProvider provider, SettingsDtos.ProviderHealthPayload payload) {
	}
}
