package com.seedshiftradio.settings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seedshiftradio.domain.ProviderType;

@Service
public class MusicGenWorkerGateway {

	private static final Duration JOB_TIMEOUT = Duration.ofSeconds(180);
	private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

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
						provider.capabilities()))
				.toList();
		if (providers.isEmpty()) {
			throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "MusicGen provider が設定されていません。");
		}
		return providers;
	}

	public SubmittedMusicJob submitWithFallback(List<ResolvedMusicProvider> providers, MusicJobRequest request) {
		MusicGenWorkerException lastFailure = null;
		for (int index = 0; index < providers.size(); index++) {
			ResolvedMusicProvider provider = providers.get(index);
			try {
				WorkerSubmitResponse response = submit(provider, request);
				if (response.jobId() == null || response.jobId().isBlank()) {
					throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "MusicGen worker が jobId を返しませんでした。");
				}
				return new SubmittedMusicJob(response.jobId(), response.status(), provider);
			} catch (MusicGenWorkerException exception) {
				lastFailure = exception;
				boolean canRetryWithFallback = index < providers.size() - 1 && isFallbackCandidate(exception);
				if (!canRetryWithFallback) {
					throw exception;
				}
			}
		}
		throw lastFailure == null
				? new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "MusicGen provider が設定されていません。")
				: lastFailure;
	}

	public MusicJobStatus awaitCompletion(ResolvedMusicProvider provider, String jobId) {
		Instant deadline = Instant.now().plus(JOB_TIMEOUT);
		while (true) {
			MusicJobStatus status = fetch(provider, jobId);
			switch (status.status()) {
				case "SUCCEEDED" -> {
					if (status.assetPath() == null || status.assetPath().isBlank()) {
						throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "MusicGen worker 成功応答に assetPath がありません。");
					}
					return status;
				}
				case "FAILED", "CANCELLED" -> throw new MusicGenWorkerException(
						status.errorCode() == null || status.errorCode().isBlank() ? "PROVIDER_BAD_RESPONSE" : status.errorCode(),
						status.message() == null || status.message().isBlank() ? "MusicGen worker ジョブが失敗しました。" : status.message());
				case "QUEUED", "RUNNING" -> {
					if (Instant.now().isAfter(deadline)) {
						throw new MusicGenWorkerException("PROVIDER_TIMEOUT", "MusicGen worker の完了待ちがタイムアウトしました。");
					}
					sleep();
				}
				default -> throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "MusicGen worker が未知の状態を返しました: " + status.status());
			}
		}
	}

	private MusicJobStatus fetch(ResolvedMusicProvider provider, String jobId) {
		HttpRequest httpRequest = jsonRequest(provider, "/music/jobs/" + jobId, "GET", null);
		WorkerJobStatusResponse response = send(httpRequest, provider, WorkerJobStatusResponse.class, "poll");
		return new MusicJobStatus(
				response.jobId(),
				response.status(),
				response.assetPath(),
				response.durationSec(),
				response.providerFingerprint(),
				response.promptHash(),
				response.errorCode(),
				response.message());
	}

	private WorkerSubmitResponse submit(ResolvedMusicProvider provider, MusicJobRequest request) {
		HttpRequest httpRequest = jsonRequest(
				provider,
				"/music/jobs",
				"POST",
				serialize(request));
		return send(httpRequest, provider, WorkerSubmitResponse.class, "submit");
	}

	private HttpRequest jsonRequest(ResolvedMusicProvider provider, String path, String method, String body) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(provider.baseUrl() + path))
				.timeout(Duration.ofMillis(provider.timeoutMs()))
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

	private <T> T send(HttpRequest request, ResolvedMusicProvider provider, Class<T> responseType, String operation) {
		try {
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofMillis(provider.timeoutMs()))
					.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw httpFailure(operation, response.statusCode(), response.body());
			}
			return objectMapper.readValue(response.body(), responseType);
		} catch (HttpTimeoutException exception) {
			throw new MusicGenWorkerException("PROVIDER_TIMEOUT", "MusicGen worker がタイムアウトしました。", exception);
		} catch (JsonProcessingException exception) {
			throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "MusicGen worker 応答 JSON の解析に失敗しました。", exception);
		} catch (IOException exception) {
			throw new MusicGenWorkerException("PROVIDER_UNREACHABLE", "MusicGen worker に接続できません。", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new MusicGenWorkerException("PROVIDER_INTERRUPTED", "MusicGen worker の待機中に割り込みが発生しました。", exception);
		}
	}

	private String serialize(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new MusicGenWorkerException("PROVIDER_BAD_RESPONSE", "MusicGen worker 送信 payload のシリアライズに失敗しました。", exception);
		}
	}

	private MusicGenWorkerException httpFailure(String operation, int statusCode, String body) {
		String errorCode;
		if (statusCode == 408 || statusCode == 504) {
			errorCode = "PROVIDER_TIMEOUT";
		} else if (statusCode == 429 || statusCode == 503) {
			errorCode = "PROVIDER_RESOURCE_EXHAUSTED";
		} else if (statusCode >= 400 && statusCode < 500) {
			errorCode = "PROVIDER_REJECTED";
		} else {
			errorCode = "PROVIDER_BAD_RESPONSE";
		}
		String message = body == null || body.isBlank()
				? "MusicGen worker " + operation + " が HTTP " + statusCode + " を返しました。"
				: "MusicGen worker " + operation + " が HTTP " + statusCode + " を返しました。 body=" + body;
		return new MusicGenWorkerException(errorCode, message);
	}

	private void sleep() {
		try {
			Thread.sleep(POLL_INTERVAL);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new MusicGenWorkerException("PROVIDER_INTERRUPTED", "MusicGen worker の待機中に割り込みが発生しました。", exception);
		}
	}

	private boolean isFallbackCandidate(MusicGenWorkerException exception) {
		return switch (exception.errorCode()) {
			case "PROVIDER_UNREACHABLE", "PROVIDER_TIMEOUT", "PROVIDER_BAD_RESPONSE", "PROVIDER_RESOURCE_EXHAUSTED" -> true;
			default -> false;
		};
	}

	public record ResolvedMusicProvider(String providerKey, String baseUrl, int timeoutMs, List<String> capabilities) {
	}

	public record MusicJobRequest(
			String requestId,
			String stationId,
			String mode,
			String genre,
			List<String> mood,
			Integer durationSec,
			Integer seed) {
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
			String errorCode,
			String message) {
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
			String errorCode,
			String message) {
	}
}
