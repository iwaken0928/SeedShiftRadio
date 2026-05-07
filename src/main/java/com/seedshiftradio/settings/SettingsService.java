package com.seedshiftradio.settings;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.seedshiftradio.common.api.ApiException;

@Service
public class SettingsService {

	private static final Set<String> CACHE_REUSE_SCOPES = Set.of("DISABLED", "SESSION", "STATION", "GLOBAL", "ARCHIVE_ONLY");
	private static final Set<String> MUSIC_PROVIDER_ADAPTERS = Set.of("MUSICGEN_WORKER", "ACE_STEP");
	private static final Set<String> MUSIC_OUTPUT_FORMATS = Set.of("wav", "wav32");
	private static final Set<String> LYRICS_TRANSLITERATION_MODES = Set.of("native", "kana", "romaji");
	private static final List<String> SECRET_REF_PREFIXES = List.of("env:", "file:");

	private final RadioSettingsStore settingsStore;
	private final ProviderHealthService providerHealthService;

	public SettingsService(RadioSettingsStore settingsStore, ProviderHealthService providerHealthService) {
		this.settingsStore = settingsStore;
		this.providerHealthService = providerHealthService;
	}

	public SettingsDtos.SettingsResponse getSettings() {
		SettingsDocument document = settingsStore.load();
		return SettingsDtos.SettingsResponse.fromDocument(settingsStore.configPath(), document);
	}

	public SettingsDtos.SettingsResponse updateSettings(SettingsDtos.SettingsUpdateRequest request) {
		SettingsDocument current = settingsStore.load();
		if (!current.version().equals(request.version())) {
			throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "設定が他で更新されています。", Map.of("field", "version"));
		}
		if (!current.schemaVersion().equals(request.schemaVersion())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "schemaVersion が現在の設定と一致しません。", Map.of("field", "schemaVersion"));
		}

		SettingsDocument merged = new SettingsDocument(
				current.version(),
				request.schemaVersion(),
				current.updatedAt(),
				request.server() == null ? current.server() : request.server(),
				request.paths() == null ? current.paths() : request.paths(),
				request.playout() == null ? current.playout() : request.playout(),
				request.cache() == null ? current.cache() : request.cache(),
				request.programming() == null ? current.programming() : request.programming(),
				request.providers() == null ? current.providers() : request.providers(),
				request.security() == null ? current.security() : request.security(),
				request.features() == null ? current.features() : request.features())
				.normalize()
				.withVersionAndTimestamp(current.version() + 1, Instant.now());

		validate(merged);
		SettingsDocument saved = settingsStore.save(merged);
		return SettingsDtos.SettingsResponse.fromDocument(settingsStore.configPath(), saved);
	}

	public SettingsDtos.ConnectionTestResponse testConnections() {
		Map<String, SettingsDtos.ProviderHealthPayload> providers = providerHealthService.refreshHealth();
		Instant checkedAt = providers.values().stream()
				.map(SettingsDtos.ProviderHealthPayload::lastCheckedAt)
				.filter(java.util.Objects::nonNull)
				.max(Instant::compareTo)
				.orElseGet(Instant::now);
		return new SettingsDtos.ConnectionTestResponse(checkedAt, providers);
	}

	private void validate(SettingsDocument document) {
		if (document.server().port() == null || document.server().port() < 1 || document.server().port() > 65535) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "server.port は 1 から 65535 の範囲で指定してください。", Map.of("field", "server.port"));
		}

		Path dataRoot = resolve(document.paths().dataRoot(), "paths.dataRoot");
		Path musicLibrary = resolve(document.paths().musicLibrary(), "paths.musicLibrary");
		if (!musicLibrary.startsWith(dataRoot)) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					"paths.musicLibrary は paths.dataRoot 配下である必要があります。",
					Map.of("field", "paths.musicLibrary"));
		}

		validatePlayout(document.playout());
		validateProviderGroup("providers.llm", document.providers().llm());
		validateProviderGroup("providers.tts", document.providers().tts());
		validateProviderGroup("providers.musicGen", document.providers().musicGen());
		validateCache(document.cache());
		validateSecretRef(document.security().adminTokenRef(), "security.adminTokenRef");
	}

	private void validatePlayout(SettingsDocument.PlayoutSettings playout) {
		validatePositive(playout.targetReadyCount(), "playout.targetReadyCount");
		validateNonNegative(playout.minimumReadyCount(), "playout.minimumReadyCount");
		validatePositive(playout.minReadyDurationMs(), "playout.minReadyDurationMs");
		validatePositive(playout.maxPreparedDurationMs(), "playout.maxPreparedDurationMs");
		validateNonNegative(playout.maxPreparedBlocks(), "playout.maxPreparedBlocks");
		validateNonNegative(playout.scriptAheadCount(), "playout.scriptAheadCount");
		validateNonNegative(playout.ttsAheadCount(), "playout.ttsAheadCount");
		validateNonNegative(playout.musicAheadCount(), "playout.musicAheadCount");
		if (playout.minimumReadyCount() > playout.targetReadyCount()) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					"playout.minimumReadyCount は playout.targetReadyCount 以下で指定してください。",
					Map.of("field", "playout.minimumReadyCount"));
		}
		if (playout.maxPreparedDurationMs() < playout.minReadyDurationMs()) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					"playout.maxPreparedDurationMs は playout.minReadyDurationMs 以上で指定してください。",
					Map.of("field", "playout.maxPreparedDurationMs"));
		}
	}

	private void validateProviderGroup(String field, SettingsDocument.ProviderGroup group) {
		if (group == null || group.providers() == null || group.providers().isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " は 1 件以上の provider が必要です。", Map.of("field", field));
		}
		if (!group.providers().containsKey(group.defaultProvider())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + ".defaultProvider が providers に存在しません。", Map.of("field", field + ".defaultProvider"));
		}
		for (String fallbackProvider : group.fallbackProviders()) {
			if (!group.providers().containsKey(fallbackProvider)) {
				throw new ApiException(
						HttpStatus.BAD_REQUEST,
						"VALIDATION_ERROR",
						field + ".fallbackProviders に providers 未登録の key が含まれています。",
						Map.of("field", field + ".fallbackProviders", "providerKey", fallbackProvider));
			}
		}
		for (Map.Entry<String, SettingsDocument.ProviderEndpoint> entry : group.providers().entrySet()) {
			SettingsDocument.ProviderEndpoint endpoint = entry.getValue();
			try {
				java.net.URI uri = java.net.URI.create(endpoint.baseUrl());
				String scheme = uri.getScheme();
				if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
					throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + "." + entry.getKey() + ".baseUrl は http/https のみ指定できます。", Map.of("field", field + "." + entry.getKey() + ".baseUrl"));
				}
			} catch (IllegalArgumentException exception) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + "." + entry.getKey() + ".baseUrl が不正です。", Map.of("field", field + "." + entry.getKey() + ".baseUrl"));
			}
			if (endpoint.healthPath() == null || endpoint.healthPath().isBlank()) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + "." + entry.getKey() + ".healthPath は必須です。", Map.of("field", field + "." + entry.getKey() + ".healthPath"));
			}
			validateSecretRef(endpoint.apiKeyRef(), field + "." + entry.getKey() + ".apiKeyRef");
			if ("providers.musicGen".equals(field)) {
				validateMusicProviderEndpoint(field + "." + entry.getKey(), endpoint);
			}
		}
	}

	private void validateMusicProviderEndpoint(String field, SettingsDocument.ProviderEndpoint endpoint) {
		if (!MUSIC_PROVIDER_ADAPTERS.contains(endpoint.adapter())) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					field + ".adapter は MUSICGEN_WORKER または ACE_STEP のいずれかで指定してください。",
					Map.of("field", field + ".adapter", "value", endpoint.adapter()));
		}
		Map<String, SettingsDocument.MusicGenerationModelProfile> profiles = endpoint.modelProfiles() == null ? Map.of() : endpoint.modelProfiles();
		if (endpoint.defaultModelProfileId() != null && !endpoint.defaultModelProfileId().isBlank() && !profiles.containsKey(endpoint.defaultModelProfileId())) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					field + ".defaultModelProfileId が modelProfiles に存在しません。",
					Map.of("field", field + ".defaultModelProfileId", "profileId", endpoint.defaultModelProfileId()));
		}
		for (Map.Entry<String, SettingsDocument.MusicGenerationModelProfile> entry : profiles.entrySet()) {
			validateMusicGenerationProfile(field + ".modelProfiles." + entry.getKey(), entry.getValue());
		}
	}

	private void validateMusicGenerationProfile(String field, SettingsDocument.MusicGenerationModelProfile profile) {
		if (profile.model() == null || profile.model().isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + ".model は必須です。", Map.of("field", field + ".model"));
		}
		if (profile.lyricsLanguage() == null || profile.lyricsLanguage().isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + ".lyricsLanguage は必須です。", Map.of("field", field + ".lyricsLanguage"));
		}
		if (!LYRICS_TRANSLITERATION_MODES.contains(profile.lyricsTransliterationMode())) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					field + ".lyricsTransliterationMode は native, kana, romaji のいずれかで指定してください。",
					Map.of("field", field + ".lyricsTransliterationMode"));
		}
		if (!MUSIC_OUTPUT_FORMATS.contains(profile.outputFormat())) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					field + ".outputFormat は wav または wav32 で指定してください。",
					Map.of("field", field + ".outputFormat", "value", profile.outputFormat()));
		}
		if (profile.maxDurationSeconds() == null || profile.maxDurationSeconds() < 10 || profile.maxDurationSeconds() > 600) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					field + ".maxDurationSeconds は 10 から 600 の範囲で指定してください。",
					Map.of("field", field + ".maxDurationSeconds"));
		}
	}

	private void validateCache(SettingsDocument.CacheSettings cache) {
		validatePositive(cache.scriptMaxBytes(), "cache.scriptMaxBytes");
		validatePositive(cache.ttsMaxBytes(), "cache.ttsMaxBytes");
		validatePositive(cache.musicMaxBytes(), "cache.musicMaxBytes");
		validateNonNegative(cache.scriptRetentionDays(), "cache.scriptRetentionDays");
		validateNonNegative(cache.ttsRetentionDays(), "cache.ttsRetentionDays");
		validateNonNegative(cache.musicRetentionDays(), "cache.musicRetentionDays");
		validatePositive(cache.cleanupBatchSize(), "cache.cleanupBatchSize");
		validateReuseScope(cache.scriptReuseScope(), "cache.scriptReuseScope");
		validateReuseScope(cache.ttsReuseScope(), "cache.ttsReuseScope");
		validateReuseScope(cache.musicReuseScope(), "cache.musicReuseScope");
	}

	private void validatePositive(Number value, String field) {
		if (value == null || value.longValue() < 1L) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " は 1 以上で指定してください。", Map.of("field", field));
		}
	}

	private void validateNonNegative(Number value, String field) {
		if (value == null || value.longValue() < 0L) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " は 0 以上で指定してください。", Map.of("field", field));
		}
	}

	private void validateReuseScope(String reuseScope, String field) {
		if (!CACHE_REUSE_SCOPES.contains(reuseScope)) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					field + " は " + String.join(", ", List.copyOf(CACHE_REUSE_SCOPES)) + " のいずれかで指定してください。",
					Map.of("field", field, "value", reuseScope));
		}
	}

	private void validateSecretRef(String value, String field) {
		if (value == null || value.isBlank()) {
			return;
		}
		boolean supported = SECRET_REF_PREFIXES.stream().anyMatch(value::startsWith);
		if (!supported) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					field + " は env: または file: 参照で指定してください。",
					Map.of("field", field));
		}
	}

	private Path resolve(String rawPath, String field) {
		try {
			return Path.of(rawPath).toAbsolutePath().normalize();
		} catch (RuntimeException exception) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " が不正です。", Map.of("field", field));
		}
	}
}
