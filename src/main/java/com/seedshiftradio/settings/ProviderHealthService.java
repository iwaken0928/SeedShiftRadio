package com.seedshiftradio.settings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.seedshiftradio.stream.StreamEventService;

@Service
public class ProviderHealthService {

	private final RadioSettingsStore settingsStore;
	private final StreamEventService streamEventService;

	private volatile Map<String, SettingsDtos.ProviderHealthPayload> latestSnapshot = Map.of();

	public ProviderHealthService(RadioSettingsStore settingsStore, StreamEventService streamEventService) {
		this.settingsStore = settingsStore;
		this.streamEventService = streamEventService;
	}

	public synchronized Map<String, SettingsDtos.ProviderHealthPayload> refreshHealth() {
		SettingsDocument settings = settingsStore.load();
		Map<String, SettingsDtos.ProviderHealthPayload> current = probeAll(settings.providers());
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

	private Map<String, SettingsDtos.ProviderHealthPayload> probeAll(SettingsDocument.ProviderCatalog providers) {
		Map<String, SettingsDtos.ProviderHealthPayload> result = new LinkedHashMap<>();
		result.put("llm", probe("llm", providers.llm()));
		result.put("tts", probe("tts", providers.tts()));
		result.put("musicGen", probe("musicGen", providers.musicGen()));
		return result;
	}

	private SettingsDtos.ProviderHealthPayload probe(String providerType, SettingsDocument.ProviderGroup group) {
		if (group == null || group.providers() == null || group.providers().isEmpty()) {
			return down(providerType, null, null, "Provider が未設定です。", Instant.now(), 0L, List.of());
		}
		SettingsDocument.ProviderEndpoint endpoint = group.providers().get(group.defaultProvider());
		if (endpoint == null) {
			return down(providerType, group.defaultProvider(), null, "defaultProvider が providers に存在しません。", Instant.now(), 0L, List.of());
		}

		String baseUrl = trimTrailingSlash(endpoint.baseUrl());
		String healthPath = endpoint.healthPath().startsWith("/") ? endpoint.healthPath() : "/" + endpoint.healthPath();
		Instant checkedAt = Instant.now();
		long startedAt = System.nanoTime();
		try {
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofMillis(endpoint.timeoutMs()))
					.build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + healthPath))
					.GET()
					.timeout(Duration.ofMillis(endpoint.timeoutMs()))
					.build();
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			long responseTimeMs = elapsedMillis(startedAt);
			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				return new SettingsDtos.ProviderHealthPayload(
						providerType,
						group.defaultProvider(),
						"UP",
						checkedAt,
						responseTimeMs,
						"接続成功",
						endpoint.capabilities(),
						baseUrl);
			}
			return new SettingsDtos.ProviderHealthPayload(
					providerType,
					group.defaultProvider(),
					"DEGRADED",
					checkedAt,
					responseTimeMs,
					"HTTP " + response.statusCode(),
					endpoint.capabilities(),
					baseUrl);
		} catch (IllegalArgumentException exception) {
			return down(providerType, group.defaultProvider(), baseUrl, "無効な URL です。", checkedAt, elapsedMillis(startedAt), endpoint.capabilities());
		} catch (HttpTimeoutException exception) {
			return down(providerType, group.defaultProvider(), baseUrl, "PROVIDER_TIMEOUT", checkedAt, elapsedMillis(startedAt), endpoint.capabilities());
		} catch (IOException exception) {
			return down(providerType, group.defaultProvider(), baseUrl, "PROVIDER_UNREACHABLE", checkedAt, elapsedMillis(startedAt), endpoint.capabilities());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return down(providerType, group.defaultProvider(), baseUrl, "PROVIDER_INTERRUPTED", checkedAt, elapsedMillis(startedAt), endpoint.capabilities());
		}
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
				baseUrl);
	}

	private String trimTrailingSlash(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return "http://127.0.0.1";
		}
		return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
	}

	private long elapsedMillis(long startedAt) {
		return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
	}
}
