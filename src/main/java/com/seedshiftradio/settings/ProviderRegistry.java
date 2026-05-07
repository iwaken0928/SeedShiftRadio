package com.seedshiftradio.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;

import com.seedshiftradio.domain.ProviderType;

@Service
public class ProviderRegistry {

	private final RadioSettingsStore settingsStore;

	public ProviderRegistry(RadioSettingsStore settingsStore) {
		this.settingsStore = settingsStore;
	}

	public List<ResolvedProvider> resolveChain(ProviderType providerType) {
		SettingsDocument.ProviderGroup group = group(providerType);
		if (group == null || group.providers() == null || group.providers().isEmpty()) {
			return List.of();
		}

		List<String> orderedKeys = new ArrayList<>();
		orderedKeys.add(group.defaultProvider());
		if (group.fallbackProviders() != null) {
			orderedKeys.addAll(group.fallbackProviders());
		}

		List<ResolvedProvider> resolved = new ArrayList<>();
		for (String providerKey : new LinkedHashSet<>(orderedKeys)) {
			if (providerKey == null || providerKey.isBlank()) {
				continue;
			}
			SettingsDocument.ProviderEndpoint endpoint = group.providers().get(providerKey);
			if (endpoint == null) {
				continue;
			}
			resolved.add(new ResolvedProvider(
					providerType,
					providerGroupKey(providerType),
					providerKey,
					trimTrailingSlash(endpoint.baseUrl()),
					normalizeHealthPath(endpoint.healthPath()),
					endpoint.timeoutMs(),
					endpoint.capabilities(),
					endpoint.adapter(),
					endpoint.apiKeyRef(),
					endpoint.defaultModelProfileId(),
					endpoint.modelProfiles(),
					!providerKey.equals(group.defaultProvider())));
		}
		return List.copyOf(resolved);
	}

	public String providerGroupKey(ProviderType providerType) {
		return switch (providerType) {
			case LLM -> "llm";
			case TTS -> "tts";
			case MUSIC -> "musicGen";
		};
	}

	private SettingsDocument.ProviderGroup group(ProviderType providerType) {
		SettingsDocument.ProviderCatalog catalog = settingsStore.load().providers();
		return switch (providerType) {
			case LLM -> catalog.llm();
			case TTS -> catalog.tts();
			case MUSIC -> catalog.musicGen();
		};
	}

	private String trimTrailingSlash(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return "http://127.0.0.1";
		}
		return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
	}

	private String normalizeHealthPath(String healthPath) {
		if (healthPath == null || healthPath.isBlank()) {
			return "/health";
		}
		return healthPath.startsWith("/") ? healthPath : "/" + healthPath;
	}

	public record ResolvedProvider(
			ProviderType providerType,
			String providerGroupKey,
			String providerKey,
			String baseUrl,
			String healthPath,
			int timeoutMs,
			List<String> capabilities,
			String adapter,
			String apiKeyRef,
			String defaultModelProfileId,
			java.util.Map<String, SettingsDocument.MusicGenerationModelProfile> modelProfiles,
			boolean fallback) {

		public ResolvedProvider(
				ProviderType providerType,
				String providerGroupKey,
				String providerKey,
				String baseUrl,
				String healthPath,
				int timeoutMs,
				List<String> capabilities,
				boolean fallback) {
			this(providerType, providerGroupKey, providerKey, baseUrl, healthPath, timeoutMs, capabilities, null, null, null, java.util.Map.of(), fallback);
		}
	}
}
