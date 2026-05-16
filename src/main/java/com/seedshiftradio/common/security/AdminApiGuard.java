package com.seedshiftradio.common.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.settings.RadioSettingsStore;
import com.seedshiftradio.settings.SettingsDocument;

@Component
public class AdminApiGuard {

	public static final String HEADER_NAME = "X-Admin-Token";

	private final AdminTokenProperties properties;
	private final RadioSettingsStore settingsStore;

	public AdminApiGuard(AdminTokenProperties properties, RadioSettingsStore settingsStore) {
		this.properties = properties;
		this.settingsStore = settingsStore;
	}

	public void require(String suppliedToken) {
		String configuredToken = resolveConfiguredToken();
		if (configuredToken == null || configuredToken.isBlank()) {
			throw new ApiException(
					HttpStatus.UNAUTHORIZED,
					"ADMIN_AUTH_REQUIRED",
					"管理 API を利用するには管理トークンの設定が必要です。",
					Map.of("header", HEADER_NAME));
		}
		if (suppliedToken == null || !configuredToken.equals(suppliedToken)) {
			throw new ApiException(
					HttpStatus.UNAUTHORIZED,
					"ADMIN_AUTH_REQUIRED",
					"管理トークンが不足しているか正しくありません。",
					Map.of("header", HEADER_NAME));
		}
	}

	private String resolveConfiguredToken() {
		if (properties.isConfigured()) {
			return properties.adminToken();
		}
		SettingsDocument document = settingsStore.load();
		return resolveSecretRef(document.security().adminTokenRef());
	}

	private String resolveSecretRef(String secretRef) {
		if (secretRef == null || secretRef.isBlank()) {
			return null;
		}
		if (secretRef.startsWith("env:")) {
			return System.getenv(secretRef.substring("env:".length()));
		}
		if (secretRef.startsWith("file:")) {
			try {
				return Files.readString(Path.of(secretRef.substring("file:".length()))).trim();
			} catch (IOException exception) {
				return null;
			}
		}
		return null;
	}
}
