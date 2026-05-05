package com.seedshiftradio.settings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seedshiftradio.domain.ProviderType;

@Service
public class MusicGenWorkerGateway implements MusicGenerationProvider {

	private static final Duration JOB_TIMEOUT = Duration.ofSeconds(180);
	private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
	private static final String ADAPTER_ACE_STEP = "ACE_STEP";
	private static final String ADAPTER_MUSICGEN_WORKER = "MUSICGEN_WORKER";

	private final ProviderRegistry providerRegistry;
	private final ObjectMapper objectMapper;

	public MusicGenWorkerGateway(ProviderRegistry providerRegistry, ObjectMapper objectMapper) {
		this.providerRegistry = providerRegistry;
		this.objectMapper = objectMapper;
	}

	public List<ResolvedMusicProvider> resolveProviders() {
		List<ResolvedMusicProvider> providers = providerRegistry.resolveChain(ProviderType.MUSIC).stream()
				.map(provider -> new ResolvedMusicProvider(
						provider.providerKey(),
						provider.baseUrl(),
						provider.timeoutMs(),
						provider.capabilities(),
						provider.adapter(),
						provider.apiKeyRef(),
						provider.defaultModelProfileId(),
						provider.modelProfiles()))
				.toList();
		if (providers.isEmpty()) {
			throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "音楽生成 provider が設定されていません。");
		}
		return providers;
	}

	public SubmittedMusicJob submitWithFallback(List<ResolvedMusicProvider> providers, MusicJobRequest request) {
		return submitWithFallback(providers, request.toGenerationRequest());
	}

	public SubmittedMusicJob submitWithFallback(List<ResolvedMusicProvider> providers, MusicGenerationRequest request) {
		MusicGenWorkerException lastFailure = null;
		for (int index = 0; index < providers.size(); index++) {
			ResolvedMusicProvider provider = providers.get(index);
			try {
				SubmittedMusicJob response = submit(provider, request);
				if (response.jobId() == null || response.jobId().isBlank()) {
					throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "音楽生成 provider が jobId を返しませんでした。");
				}
				return response;
			} catch (MusicGenWorkerException exception) {
				lastFailure = exception;
				boolean canRetryWithFallback = index < providers.size() - 1 && isFallbackCandidate(exception);
				if (!canRetryWithFallback) {
					throw exception;
				}
			}
		}
		throw lastFailure == null
				? new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "音楽生成 provider が設定されていません。")
				: lastFailure;
	}

	public MusicJobStatus awaitCompletion(ResolvedMusicProvider provider, String jobId) {
		Instant deadline = Instant.now().plus(JOB_TIMEOUT);
		while (true) {
			MusicJobStatus status = poll(provider, jobId);
			switch (status.status()) {
				case "SUCCEEDED" -> {
					if (status.assetPath() == null || status.assetPath().isBlank()) {
						throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "音楽生成 provider 成功応答に assetPath がありません。");
					}
					return status;
				}
				case "FAILED", "CANCELLED" -> throw new MusicGenWorkerException(
						status.errorCode() == null || status.errorCode().isBlank() ? "PROVIDER_BAD_RESPONSE" : status.errorCode(),
						status.message() == null || status.message().isBlank() ? "音楽生成ジョブが失敗しました。" : status.message());
				case "QUEUED", "RUNNING" -> {
					if (Instant.now().isAfter(deadline)) {
						throw new MusicGenWorkerException("PROVIDER_TIMEOUT", "音楽生成 provider の完了待ちがタイムアウトしました。");
					}
					sleep();
				}
				default -> throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "音楽生成 provider が未知の状態を返しました: " + status.status());
			}
		}
	}

	@Override
	public ModelCatalog listModels(ResolvedMusicProvider provider) {
		if (!provider.isAceStep()) {
			return new ModelCatalog(List.of(), null);
		}
		HttpRequest request = authedRequest(provider, "/v1/models").GET().build();
		JsonNode root = sendJson(request, provider, "models");
		JsonNode data = root.path("data");
		List<ModelInfo> models = objectMapper.convertValue(
				data.path("models"),
				objectMapper.getTypeFactory().constructCollectionType(List.class, ModelInfo.class));
		return new ModelCatalog(models == null ? List.of() : models, textOrNull(data.path("default_model")));
	}

	@Override
	public RuntimeStats stats(ResolvedMusicProvider provider) {
		if (!provider.isAceStep()) {
			return new RuntimeStats(null, null, null, null);
		}
		HttpRequest request = authedRequest(provider, "/v1/stats").GET().build();
		JsonNode data = sendJson(request, provider, "stats").path("data");
		JsonNode jobs = data.path("jobs");
		return new RuntimeStats(
				intOrNull(jobs.path("queued")),
				intOrNull(jobs.path("running")),
				intOrNull(data.path("queue_size")),
				doubleOrNull(data.path("avg_job_seconds")));
	}

	@Override
	public MusicJobStatus poll(ResolvedMusicProvider provider, String jobId) {
		if (provider.isAceStep()) {
			return fetchAceStep(provider, jobId);
		}
		HttpRequest httpRequest = jsonRequest(provider, "/music/jobs/" + jobId, "GET", null);
		WorkerJobStatusResponse response = send(httpRequest, provider, WorkerJobStatusResponse.class, "poll");
		return new MusicJobStatus(
				response.jobId(),
				response.status(),
				response.assetPath(),
				response.durationSec(),
				response.providerFingerprint(),
				response.promptHash(),
				response.lyricsHash(),
				response.errorCode(),
				response.message(),
				response.model(),
				response.lmModel(),
				response.seed());
	}

	@Override
	public SubmittedMusicJob submit(ResolvedMusicProvider provider, MusicGenerationRequest request) {
		if (provider.isAceStep()) {
			return submitAceStep(provider, request);
		}
		WorkerSubmitResponse response = submitWorker(provider, request);
		return new SubmittedMusicJob(response.jobId(), response.status(), provider);
	}

	private WorkerSubmitResponse submitWorker(ResolvedMusicProvider provider, MusicGenerationRequest request) {
		HttpRequest httpRequest = jsonRequest(
				provider,
				"/music/jobs",
				"POST",
				serialize(request));
		return send(httpRequest, provider, WorkerSubmitResponse.class, "submit");
	}

	private SubmittedMusicJob submitAceStep(ResolvedMusicProvider provider, MusicGenerationRequest rawRequest) {
		SettingsDocument.MusicGenerationModelProfile profile = provider.profile(rawRequest.modelProfileId());
		MusicGenerationRequest request = rawRequest.normalize(profile);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("prompt", request.prompt());
		payload.put("lyrics", request.lyrics());
		payload.put("vocal_language", request.lyricsLanguage());
		payload.put("thinking", profile.thinking());
		payload.put("model", profile.model());
		payload.put("lm_model_path", profile.lmModel());
		payload.put("audio_duration", request.durationSeconds());
		payload.put("audio_format", request.outputFormat());
		payload.put("use_format", true);
		payload.put("use_cot_caption", true);
		payload.put("use_cot_language", false);
		payload.put("batch_size", 1);
		if (request.bpm() != null) {
			payload.put("bpm", request.bpm());
		}
		if (request.keyScale() != null && !request.keyScale().isBlank()) {
			payload.put("key_scale", request.keyScale());
		}
		if (request.timeSignature() != null && !request.timeSignature().isBlank()) {
			payload.put("time_signature", request.timeSignature());
		}
		if (request.seed() != null) {
			payload.put("use_random_seed", false);
			payload.put("seed", request.seed());
		}
		HttpRequest httpRequest = authedRequest(provider, "/release_task")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(serialize(payload)))
				.build();
		JsonNode data = sendJson(httpRequest, provider, "submit").path("data");
		String taskId = textOrNull(data.path("task_id"));
		return new SubmittedMusicJob(taskId, textOrNull(data.path("status")), provider);
	}

	private MusicJobStatus fetchAceStep(ResolvedMusicProvider provider, String jobId) {
		Map<String, Object> payload = Map.of("task_id_list", List.of(jobId));
		HttpRequest httpRequest = authedRequest(provider, "/query_result")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(serialize(payload)))
				.build();
		JsonNode root = sendJson(httpRequest, provider, "poll");
		JsonNode first = root.path("data").isArray() && !root.path("data").isEmpty() ? root.path("data").get(0) : null;
		if (first == null || first.isMissingNode()) {
			throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "ACE-Step が query_result data を返しませんでした。");
		}
		int status = first.path("status").asInt(0);
		if (status == 0) {
			return new MusicJobStatus(jobId, "RUNNING", null, null, null, null, null, null, null, null, null, null);
		}
		if (status == 2) {
			return new MusicJobStatus(jobId, "FAILED", null, null, null, null, null, "PROVIDER_BAD_RESPONSE", "ACE-Step task failed", null, null, null);
		}
		JsonNode result = parseAceResult(first.path("result"));
		String audioUrl = textOrNull(result.path("file"));
		if (audioUrl == null || audioUrl.isBlank()) {
			throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "ACE-Step 成功応答に audio file URL がありません。");
		}
		Path downloaded = downloadAceStepAudio(provider, audioUrl, result);
		JsonNode metas = result.path("metas");
		String ditModel = textOrNull(result.path("dit_model"));
		String lmModel = textOrNull(result.path("lm_model"));
		return new MusicJobStatus(
				jobId,
				"SUCCEEDED",
				downloaded.toString(),
				intOrNull(metas.path("duration")),
				provider.providerKey() + ":" + valueOrUnknown(ditModel) + ":" + valueOrUnknown(lmModel),
				sha256(textOrNull(result.path("prompt")) + "\n" + textOrNull(result.path("lyrics"))),
				sha256(textOrNull(result.path("lyrics"))),
				null,
				"generated",
				ditModel,
				lmModel,
				textOrNull(result.path("seed_value")));
	}

	private JsonNode parseAceResult(JsonNode resultNode) {
		try {
			if (resultNode == null || resultNode.isNull() || resultNode.isMissingNode()) {
				throw new JsonProcessingException("missing result") { };
			}
			if (resultNode.isTextual()) {
				JsonNode parsed = objectMapper.readTree(resultNode.asText());
				if (parsed.isArray()) {
					return parsed.isEmpty() ? objectMapper.createObjectNode() : parsed.get(0);
				}
				return parsed;
			}
			if (resultNode.isArray()) {
				return resultNode.isEmpty() ? objectMapper.createObjectNode() : resultNode.get(0);
			}
			return resultNode;
		} catch (JsonProcessingException exception) {
			throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "ACE-Step result JSON の解析に失敗しました。", exception);
		}
	}

	private Path downloadAceStepAudio(ResolvedMusicProvider provider, String audioUrl, JsonNode result) {
		try {
			URI uri = audioUrl.startsWith("http://") || audioUrl.startsWith("https://")
					? URI.create(audioUrl)
					: URI.create(provider.baseUrl() + (audioUrl.startsWith("/") ? audioUrl : "/" + audioUrl));
			HttpRequest request = authedRequest(provider, uri).GET().build();
			HttpResponse<byte[]> response = sendBytes(request, provider, "download");
			String extension = extensionFromResult(result, uri);
			Path path = Files.createTempFile("seedshift-ace-step-", extension);
			Files.write(path, response.body());
			return path;
		} catch (IllegalArgumentException exception) {
			throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "ACE-Step audio URL が不正です。", exception);
		} catch (IOException exception) {
			throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "ACE-Step audio の一時保存に失敗しました。", exception);
		}
	}

	private String extensionFromResult(JsonNode result, URI uri) {
		String format = textOrNull(result.path("audio_format"));
		if (format != null && !format.isBlank()) {
			return "." + format.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
		}
		String path = uri.getPath();
		int dot = path == null ? -1 : path.lastIndexOf('.');
		if (dot >= 0 && dot < path.length() - 1) {
			return path.substring(dot).replaceAll("[^A-Za-z0-9.]", "").toLowerCase(Locale.ROOT);
		}
		return ".wav";
	}

	private HttpRequest jsonRequest(ResolvedMusicProvider provider, String path, String method, String body) {
		HttpRequest.Builder builder = authedRequest(provider, path)
				.header("Accept", "application/json");
		if (body != null) {
			builder.header("Content-Type", "application/json");
		}
		if ("POST".equals(method)) {
			builder.POST(HttpRequest.BodyPublishers.ofString(body));
		} else {
			builder.GET();
		}
		return builder.build();
	}

	private HttpRequest.Builder authedRequest(ResolvedMusicProvider provider, String path) {
		return authedRequest(provider, URI.create(provider.baseUrl() + path));
	}

	private HttpRequest.Builder authedRequest(ResolvedMusicProvider provider, URI uri) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofMillis(provider.timeoutMs()))
				.header("Accept", "application/json");
		String apiKey = resolveSecret(provider.apiKeyRef());
		if (apiKey != null && !apiKey.isBlank()) {
			builder.header("Authorization", "Bearer " + apiKey);
		}
		return builder;
	}

	private <T> T send(HttpRequest request, ResolvedMusicProvider provider, Class<T> responseType, String operation) {
		String body = sendString(request, provider, operation);
		try {
			return objectMapper.readValue(body, responseType);
		} catch (JsonProcessingException exception) {
			throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "音楽生成 provider 応答 JSON の解析に失敗しました。", exception);
		}
	}

	private JsonNode sendJson(HttpRequest request, ResolvedMusicProvider provider, String operation) {
		String body = sendString(request, provider, operation);
		try {
			return objectMapper.readTree(body);
		} catch (JsonProcessingException exception) {
			throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "音楽生成 provider 応答 JSON の解析に失敗しました。", exception);
		}
	}

	private String sendString(HttpRequest request, ResolvedMusicProvider provider, String operation) {
		try {
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofMillis(provider.timeoutMs()))
					.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw httpFailure(operation, response.statusCode());
			}
			return response.body();
		} catch (HttpTimeoutException exception) {
			throw new MusicGenWorkerException("PROVIDER_TIMEOUT", "音楽生成 provider がタイムアウトしました。", exception);
		} catch (IOException exception) {
			throw new MusicGenWorkerException("PROVIDER_UNREACHABLE", "音楽生成 provider に接続できません。", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new MusicGenWorkerException("PROVIDER_INTERRUPTED", "音楽生成 provider の待機中に割り込みが発生しました。", exception);
		}
	}

	private HttpResponse<byte[]> sendBytes(HttpRequest request, ResolvedMusicProvider provider, String operation) {
		try {
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofMillis(provider.timeoutMs()))
					.build();
			HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw httpFailure(operation, response.statusCode());
			}
			return response;
		} catch (HttpTimeoutException exception) {
			throw new MusicGenWorkerException("PROVIDER_TIMEOUT", "音楽生成 provider がタイムアウトしました。", exception);
		} catch (IOException exception) {
			throw new MusicGenWorkerException("PROVIDER_UNREACHABLE", "音楽生成 provider に接続できません。", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new MusicGenWorkerException("PROVIDER_INTERRUPTED", "音楽生成 provider の待機中に割り込みが発生しました。", exception);
		}
	}

	private String serialize(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "音楽生成 provider 送信 payload のシリアライズに失敗しました。", exception);
		}
	}

	private MusicGenWorkerException httpFailure(String operation, int statusCode) {
		String errorCode;
		if (statusCode == 401 || statusCode == 403) {
			errorCode = "PROVIDER_AUTH_FAILED";
		} else if (statusCode == 408 || statusCode == 504) {
			errorCode = "PROVIDER_TIMEOUT";
		} else if (statusCode == 429 || statusCode == 503) {
			errorCode = "PROVIDER_RESOURCE_EXHAUSTED";
		} else if (statusCode >= 400 && statusCode < 500) {
			errorCode = "PROVIDER_REJECTED";
		} else {
			errorCode = "PROVIDER_BAD_RESPONSE";
		}
		return new MusicGenWorkerException(errorCode, "音楽生成 provider " + operation + " が HTTP " + statusCode + " を返しました。");
	}

	private String resolveSecret(String secretRef) {
		if (secretRef == null || secretRef.isBlank()) {
			return null;
		}
		if (secretRef.startsWith("env:")) {
			String value = System.getenv(secretRef.substring("env:".length()));
			if (value == null || value.isBlank()) {
				throw new MusicGenWorkerException("PROVIDER_AUTH_FAILED", "音楽生成 provider の環境変数参照を解決できません。");
			}
			return value;
		}
		if (secretRef.startsWith("file:")) {
			try {
				String value = Files.readString(Path.of(secretRef.substring("file:".length()))).trim();
				if (value.isBlank()) {
					throw new MusicGenWorkerException("PROVIDER_AUTH_FAILED", "音楽生成 provider の秘密値参照が空です。");
				}
				return value;
			} catch (IOException exception) {
				throw new MusicGenWorkerException("PROVIDER_AUTH_FAILED", "音楽生成 provider の秘密値参照を解決できません。", exception);
			}
		}
		throw new MusicGenWorkerException("PROVIDER_AUTH_FAILED", "音楽生成 provider の apiKeyRef は env: または file: で指定してください。");
	}

	private void sleep() {
		try {
			Thread.sleep(POLL_INTERVAL);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new MusicGenWorkerException("PROVIDER_INTERRUPTED", "音楽生成 provider の待機中に割り込みが発生しました。", exception);
		}
	}

	private boolean isFallbackCandidate(MusicGenWorkerException exception) {
		return switch (exception.errorCode()) {
			case "PROVIDER_UNREACHABLE", "PROVIDER_TIMEOUT", "PROVIDER_BAD_RESPONSE", "PROVIDER_RESOURCE_EXHAUSTED" -> true;
			default -> false;
		};
	}

	private Integer intOrNull(JsonNode node) {
		return node == null || node.isNull() || node.isMissingNode() ? null : node.asInt();
	}

	private Double doubleOrNull(JsonNode node) {
		return node == null || node.isNull() || node.isMissingNode() ? null : node.asDouble();
	}

	private String textOrNull(JsonNode node) {
		return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
	}

	private String valueOrUnknown(String value) {
		return value == null || value.isBlank() ? "unknown" : value;
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(bytes.length * 2);
			for (byte current : bytes) {
				builder.append(String.format("%02x", current));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 が利用できません。", exception);
		}
	}

	public record ResolvedMusicProvider(
			String providerKey,
			String baseUrl,
			int timeoutMs,
			List<String> capabilities,
			String adapter,
			String apiKeyRef,
			String defaultModelProfileId,
			Map<String, SettingsDocument.MusicGenerationModelProfile> modelProfiles) {

		public ResolvedMusicProvider(String providerKey, String baseUrl, int timeoutMs, List<String> capabilities) {
			this(providerKey, baseUrl, timeoutMs, capabilities, ADAPTER_MUSICGEN_WORKER, null, null, Map.of());
		}

		public ResolvedMusicProvider {
			adapter = adapter == null || adapter.isBlank() ? ADAPTER_MUSICGEN_WORKER : adapter.toUpperCase();
			modelProfiles = modelProfiles == null ? Map.of() : Map.copyOf(modelProfiles);
			capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
		}

		boolean isAceStep() {
			return ADAPTER_ACE_STEP.equals(adapter) || capabilities.contains("ACE_STEP");
		}

		SettingsDocument.MusicGenerationModelProfile profile(String requestedProfileId) {
			String profileId = resolvedProfileId(requestedProfileId);
			SettingsDocument.MusicGenerationModelProfile profile = profileId == null ? null : modelProfiles.get(profileId);
			if (profile == null) {
				profile = modelProfiles.get("ace-ja-fast");
			}
			return (profile == null ? SettingsDocument.MusicGenerationModelProfile.aceJaFast() : profile).normalize();
		}

		String resolvedProfileId(String requestedProfileId) {
			String profileId = requestedProfileId == null || requestedProfileId.isBlank()
					? defaultModelProfileId
					: requestedProfileId;
			return profileId == null || profileId.isBlank() ? "ace-ja-fast" : profileId;
		}
	}

	public record MusicJobRequest(
			String requestId,
			String stationId,
			String mode,
			String genre,
			List<String> mood,
			Integer durationSec,
			Integer seed) {

		static MusicJobRequest from(MusicGenerationRequest request) {
			return new MusicJobRequest(
					request.requestId(),
					request.stationId(),
					request.mode(),
					normalizeGenre(request.prompt()),
					List.of("ja", "radio", "song"),
					request.durationSeconds(),
					request.seed());
		}

		MusicGenerationRequest toGenerationRequest() {
			String prompt = "Japanese original radio music, genre=" + normalizeGenre(genre) + ", mood=" + String.join(",", mood == null ? List.of() : mood);
			return new MusicGenerationRequest(
					requestId,
					stationId,
					"radio",
					mode,
					prompt,
					"",
					"ja",
					durationSec,
					null,
					"",
					"4",
					seed,
					null,
					"wav");
		}

		private static String normalizeGenre(String value) {
			if (value == null || value.isBlank()) {
				return "ambient";
			}
			return value.replaceAll("[^A-Za-z0-9 _-]", " ").trim().toLowerCase(Locale.ROOT);
		}
	}

	public record SubmittedMusicJob(String jobId, String status, ResolvedMusicProvider provider) {
	}

	public record MusicJobStatus(
			String jobId,
			String status,
			String assetPath,
			Integer durationSec,
			String providerFingerprint,
			String promptHash,
			String lyricsHash,
			String errorCode,
			String message,
			String model,
			String lmModel,
			String seed) {
	}

	public record ModelCatalog(List<ModelInfo> models, String defaultModel) {
	}

	public record ModelInfo(String name, Boolean is_default, Boolean is_loaded) {
	}

	public record RuntimeStats(Integer queuedJobs, Integer runningJobs, Integer queueSize, Double averageJobSeconds) {
	}

	private record WorkerSubmitResponse(String jobId, String status) {
	}

	private record WorkerJobStatusResponse(
			String jobId,
			String status,
			String assetPath,
			Integer durationSec,
			String providerFingerprint,
			String promptHash,
			String lyricsHash,
			String errorCode,
			String message,
			String model,
			String lmModel,
			String seed) {
	}
}
