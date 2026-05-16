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
		return down(
				providerTypeKey,
				lastFailure.payload().providerKey(),
				lastFailure.payload().baseUrl(),
				message,
				lastFailure.payload().lastCheckedAt(),
				lastFailure.payload().responseTimeMs(),
				lastFailure.payload().capabilities());
	}

	private ProbeResult probeProvider(ProviderType providerType, ProviderRegistry.ResolvedProvider provider) {
		Instant checkedAt = Instant.now();
		long startedAt = System.nanoTime();
		try {
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofMillis(provider.timeoutMs()))
					.build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(provider.baseUrl() + provider.healthPath()))
					.GET()
					.timeout(Duration.ofMillis(provider.timeoutMs()))
					.build();
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			long responseTimeMs = elapsedMillis(startedAt);
			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				return new ProbeResult(provider, new SettingsDtos.ProviderHealthPayload(
						provider.providerGroupKey(),
						provider.providerKey(),
						"UP",
						checkedAt,
						responseTimeMs,
						"接続成功",
						provider.capabilities(),
						provider.baseUrl(),
						enrichProviderMetadata(providerType, provider)));
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
					enrichProviderMetadata(providerType, provider)));
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

	private Map<String, Object> enrichProviderMetadata(ProviderType providerType, ProviderRegistry.ResolvedProvider provider) {
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
		readAceStepStats(provider, metadata);
		readAceStepModels(provider, metadata);
		return metadata;
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
		metadata.put("voiceRefStatus", "VOICE_PROFILE_REQUIRED");
		readIrodoriModels(provider, metadata);
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
			String defaultModel = textOrNull(data.path("default_model"));
			if (defaultModel != null) {
				metadata.put("defaultModel", defaultModel);
			}
			List<String> models = new ArrayList<>();
			JsonNode modelNodes = data.path("models");
			if (modelNodes.isArray()) {
				for (JsonNode modelNode : modelNodes) {
					String name = textOrNull(modelNode.path("name"));
					if (name != null && !name.isBlank()) {
						models.add(name);
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
			return System.getenv(secretRef.substring("env:".length()));
		}
		if (secretRef.startsWith("file:")) {
			try {
				return java.nio.file.Files.readString(java.nio.file.Path.of(secretRef.substring("file:".length()))).trim();
			} catch (IOException exception) {
				return null;
			}
		}
		return null;
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
					|| !Objects.equals(before.baseUrl(), after.baseUrl())) {
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
