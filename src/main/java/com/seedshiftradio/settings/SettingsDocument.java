package com.seedshiftradio.settings;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record SettingsDocument(
		Integer version,
		String schemaVersion,
		Instant updatedAt,
		ServerSettings server,
		PathSettings paths,
		PlayoutSettings playout,
		CacheSettings cache,
		ProgrammingSettings programming,
		ProviderCatalog providers,
		SecuritySettings security,
		FeatureSettings features) {

	public SettingsDocument normalize() {
		return new SettingsDocument(
				version == null || version < 1 ? 1 : version,
				(schemaVersion == null || schemaVersion.isBlank()) ? "2026-04" : schemaVersion,
				updatedAt == null ? Instant.now() : updatedAt,
				server == null ? ServerSettings.defaults() : server.normalize(),
				paths == null ? PathSettings.defaults() : paths.normalize(),
				playout == null ? PlayoutSettings.defaults() : playout.normalize(),
				cache == null ? CacheSettings.defaults() : cache.normalize(),
				programming == null ? ProgrammingSettings.defaults() : programming.normalize(),
				providers == null ? ProviderCatalog.defaults() : providers.normalize(),
				security == null ? SecuritySettings.defaults() : security.normalize(),
				features == null ? FeatureSettings.defaults() : features.normalize());
	}

	public SettingsDocument withVersionAndTimestamp(int newVersion, Instant timestamp) {
		return new SettingsDocument(
				newVersion,
				schemaVersion,
				timestamp,
				server,
				paths,
				playout,
				cache,
				programming,
				providers,
				security,
				features);
	}

	public static SettingsDocument defaults() {
		return new SettingsDocument(
				1,
				"2026-04",
				Instant.now(),
				ServerSettings.defaults(),
				PathSettings.defaults(),
				PlayoutSettings.defaults(),
				CacheSettings.defaults(),
				ProgrammingSettings.defaults(),
				ProviderCatalog.defaults(),
				SecuritySettings.defaults(),
				FeatureSettings.defaults());
	}

	public record ServerSettings(String bindHost, Integer port) {

		public ServerSettings normalize() {
			return new ServerSettings(
					(bindHost == null || bindHost.isBlank()) ? "127.0.0.1" : bindHost,
					(port == null || port < 1) ? 8080 : port);
		}

		static ServerSettings defaults() {
			return new ServerSettings("127.0.0.1", 8080);
		}
	}

	public record PathSettings(String dataRoot, String musicLibrary) {

		public PathSettings normalize() {
			return new PathSettings(
					(dataRoot == null || dataRoot.isBlank()) ? "./data" : dataRoot,
					(musicLibrary == null || musicLibrary.isBlank()) ? "./data/library/music" : musicLibrary);
		}

		static PathSettings defaults() {
			return new PathSettings("./data", "./data/library/music");
		}
	}

	public record PlayoutSettings(Integer targetReadyCount, Integer minReadyDurationMs) {

		public PlayoutSettings normalize() {
			return new PlayoutSettings(
					targetReadyCount == null || targetReadyCount < 1 ? 3 : targetReadyCount,
					minReadyDurationMs == null || minReadyDurationMs < 1 ? 90_000 : minReadyDurationMs);
		}

		static PlayoutSettings defaults() {
			return new PlayoutSettings(3, 90_000);
		}
	}

	public record CacheSettings(
			Long scriptMaxBytes,
			Long ttsMaxBytes,
			Long musicMaxBytes,
			Integer scriptRetentionDays,
			Integer ttsRetentionDays,
			Integer musicRetentionDays,
			String scriptReuseScope,
			String ttsReuseScope,
			String musicReuseScope,
			Integer cleanupBatchSize) {

		public CacheSettings normalize() {
			return new CacheSettings(
					scriptMaxBytes == null || scriptMaxBytes < 1 ? 134_217_728L : scriptMaxBytes,
					ttsMaxBytes == null || ttsMaxBytes < 1 ? 536_870_912L : ttsMaxBytes,
					musicMaxBytes == null || musicMaxBytes < 1 ? 2_147_483_648L : musicMaxBytes,
					scriptRetentionDays == null || scriptRetentionDays < 0 ? 7 : scriptRetentionDays,
					ttsRetentionDays == null || ttsRetentionDays < 0 ? 30 : ttsRetentionDays,
					musicRetentionDays == null || musicRetentionDays < 0 ? 30 : musicRetentionDays,
					normalizeReuseScope(scriptReuseScope, "STATION"),
					normalizeReuseScope(ttsReuseScope, "STATION"),
					normalizeReuseScope(musicReuseScope, "GLOBAL"),
					cleanupBatchSize == null || cleanupBatchSize < 1 ? 200 : cleanupBatchSize);
		}

		static CacheSettings defaults() {
			return new CacheSettings(
					134_217_728L,
					536_870_912L,
					2_147_483_648L,
					7,
					30,
					30,
					"STATION",
					"STATION",
					"GLOBAL",
					200);
		}

		private static String normalizeReuseScope(String reuseScope, String fallback) {
			return (reuseScope == null || reuseScope.isBlank()) ? fallback : reuseScope.toUpperCase();
		}
	}

	public record ProgrammingSettings(Integer defaultPlanningHorizonMinutes, Boolean legacyRatioFallback, String seedImportRef) {

		public ProgrammingSettings normalize() {
			return new ProgrammingSettings(
					defaultPlanningHorizonMinutes == null || defaultPlanningHorizonMinutes < 1 ? 20 : defaultPlanningHorizonMinutes,
					legacyRatioFallback == null ? Boolean.TRUE : legacyRatioFallback,
					(seedImportRef == null || seedImportRef.isBlank()) ? "file:./data/config/programming-seed.json" : seedImportRef);
		}

		static ProgrammingSettings defaults() {
			return new ProgrammingSettings(20, true, "file:./data/config/programming-seed.json");
		}
	}

	public record ProviderCatalog(ProviderGroup llm, ProviderGroup tts, ProviderGroup musicGen) {

		public ProviderCatalog normalize() {
			return new ProviderCatalog(
					llm == null ? defaults().llm() : llm.normalize(),
					tts == null ? defaults().tts() : tts.normalize(),
					musicGen == null ? defaults().musicGen() : musicGen.normalize());
		}

		static ProviderCatalog defaults() {
			return new ProviderCatalog(
					new ProviderGroup(
							"ollama",
							List.of(),
							Map.of("ollama", new ProviderEndpoint("http://127.0.0.1:11434", "/api/tags", 5_000, List.of("SCRIPT_GEN")))),
					new ProviderGroup(
							"voicevox",
							List.of(),
							Map.of("voicevox", new ProviderEndpoint("http://127.0.0.1:50021", "/version", 5_000, List.of("TTS_GEN")))),
					new ProviderGroup(
							"ace-step",
							List.of(),
							Map.of("ace-step", new ProviderEndpoint("http://127.0.0.1:8000", "/health", 5_000, List.of("MUSIC_GEN")))));
		}
	}

	public record ProviderGroup(String defaultProvider, List<String> fallbackProviders, Map<String, ProviderEndpoint> providers) {

		public ProviderGroup normalize() {
			Map<String, ProviderEndpoint> normalizedProviders = providers == null || providers.isEmpty()
					? Map.of()
					: new LinkedHashMap<>(providers.entrySet().stream()
							.collect(java.util.stream.Collectors.toMap(
									Map.Entry::getKey,
									entry -> entry.getValue() == null ? ProviderEndpoint.defaults() : entry.getValue().normalize(),
									(left, right) -> right,
									LinkedHashMap::new)));
			String normalizedDefault = (defaultProvider == null || defaultProvider.isBlank())
					? normalizedProviders.keySet().stream().findFirst().orElse("default")
					: defaultProvider;
			List<String> normalizedFallbacks = fallbackProviders == null
					? List.of()
					: fallbackProviders.stream()
							.filter(Objects::nonNull)
							.map(String::trim)
							.filter(value -> !value.isBlank())
							.distinct()
							.filter(value -> !value.equals(normalizedDefault))
							.toList();
			return new ProviderGroup(
					normalizedDefault,
					normalizedFallbacks,
					normalizedProviders);
		}
	}

	public record ProviderEndpoint(String baseUrl, String healthPath, Integer timeoutMs, List<String> capabilities) {

		public ProviderEndpoint normalize() {
			return new ProviderEndpoint(
					(baseUrl == null || baseUrl.isBlank()) ? "http://127.0.0.1" : baseUrl,
					(healthPath == null || healthPath.isBlank()) ? "/health" : healthPath,
					timeoutMs == null || timeoutMs < 100 ? 5_000 : timeoutMs,
					capabilities == null ? List.of() : List.copyOf(capabilities));
		}

		static ProviderEndpoint defaults() {
			return new ProviderEndpoint("http://127.0.0.1", "/health", 5_000, List.of());
		}
	}

	public record SecuritySettings(String adminTokenRef) {

		public SecuritySettings normalize() {
			return new SecuritySettings(
					(adminTokenRef == null || adminTokenRef.isBlank()) ? "env:SEEDSHIFT_ADMIN_TOKEN" : adminTokenRef);
		}

		static SecuritySettings defaults() {
			return new SecuritySettings("env:SEEDSHIFT_ADMIN_TOKEN");
		}
	}

	public record FeatureSettings(StreamingFeatureSettings streaming) {

		public FeatureSettings normalize() {
			return new FeatureSettings(streaming == null ? StreamingFeatureSettings.defaults() : streaming.normalize());
		}

		static FeatureSettings defaults() {
			return new FeatureSettings(StreamingFeatureSettings.defaults());
		}
	}

	public record StreamingFeatureSettings(Boolean placeholderEnabled) {

		public StreamingFeatureSettings normalize() {
			return new StreamingFeatureSettings(placeholderEnabled == null ? Boolean.TRUE : placeholderEnabled);
		}

		static StreamingFeatureSettings defaults() {
			return new StreamingFeatureSettings(true);
		}
	}
}
