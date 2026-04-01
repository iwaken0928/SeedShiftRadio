package com.seedshiftradio.settings;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class SettingsDtos {

	private SettingsDtos() {
	}

	public record SettingsResponse(
			Integer version,
			String schemaVersion,
			Instant updatedAt,
			String configPath,
			SettingsDocument.ServerSettings server,
			SettingsDocument.PathSettings paths,
			SettingsDocument.PlayoutSettings playout,
			SettingsDocument.CacheSettings cache,
			SettingsDocument.ProgrammingSettings programming,
			SettingsDocument.ProviderCatalog providers,
			SettingsDocument.SecuritySettings security,
			SettingsDocument.FeatureSettings features) {

		public static SettingsResponse fromDocument(Path configPath, SettingsDocument document) {
			return new SettingsResponse(
					document.version(),
					document.schemaVersion(),
					document.updatedAt(),
					configPath.toString(),
					document.server(),
					document.paths(),
					document.playout(),
					document.cache(),
					document.programming(),
					document.providers(),
					document.security(),
					document.features());
		}
	}

	public record SettingsUpdateRequest(
			@NotNull Integer version,
			@NotBlank String schemaVersion,
			@Valid SettingsDocument.ServerSettings server,
			@Valid SettingsDocument.PathSettings paths,
			@Valid SettingsDocument.PlayoutSettings playout,
			@Valid SettingsDocument.CacheSettings cache,
			@Valid SettingsDocument.ProgrammingSettings programming,
			@Valid SettingsDocument.ProviderCatalog providers,
			@Valid SettingsDocument.SecuritySettings security,
			@Valid SettingsDocument.FeatureSettings features) {
	}

	public record ProviderHealthPayload(
			String providerType,
			String providerKey,
			String status,
			Instant lastCheckedAt,
			Long responseTimeMs,
			String message,
			List<String> capabilities,
			String baseUrl) {
	}

	public record ConnectionTestResponse(Instant checkedAt, Map<String, ProviderHealthPayload> providers) {

		public ConnectionTestResponse {
			providers = providers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(providers));
		}
	}
}
