package com.seedshiftradio.common.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.settings.RadioSettingsStore;
import com.seedshiftradio.settings.SettingsDocument;

@ExtendWith(MockitoExtension.class)
class AdminApiGuardTests {

	@TempDir
	Path tempDir;

	@Mock
	RadioSettingsStore settingsStore;

	@Test
	void configuredPropertyTokenTakesPrecedenceWithoutLoadingSettings() {
		AdminApiGuard guard = new AdminApiGuard(new AdminTokenProperties("property-token"), settingsStore);

		assertDoesNotThrow(() -> guard.require("property-token"));

		verify(settingsStore, never()).load();
	}

	@Test
	void fileAdminTokenRefIsResolvedWhenPropertyTokenIsNotConfigured() throws Exception {
		Path tokenFile = tempDir.resolve("admin-token.txt");
		Files.writeString(tokenFile, "file-token\n");
		SettingsDocument document = withSecurity(new SettingsDocument.SecuritySettings("file:" + tokenFile));
		when(settingsStore.load()).thenReturn(document);
		AdminApiGuard guard = new AdminApiGuard(new AdminTokenProperties(""), settingsStore);

		assertDoesNotThrow(() -> guard.require("file-token"));
	}

	@Test
	void missingResolvedTokenIsRejectedWithoutLeakingSecretRef() {
		SettingsDocument document = withSecurity(
				new SettingsDocument.SecuritySettings("env:SEEDSHIFT_MISSING_ADMIN_TOKEN_FOR_TEST"));
		when(settingsStore.load()).thenReturn(document);
		AdminApiGuard guard = new AdminApiGuard(new AdminTokenProperties(""), settingsStore);

		ApiException exception = assertThrows(ApiException.class, () -> guard.require("any-token"));

		assertEquals("ADMIN_AUTH_REQUIRED", exception.getCode());
		assertEquals(AdminApiGuard.HEADER_NAME, exception.getDetails().get("header"));
	}

	private SettingsDocument withSecurity(SettingsDocument.SecuritySettings security) {
		SettingsDocument defaults = SettingsDocument.defaults();
		return new SettingsDocument(
				defaults.version(),
				defaults.schemaVersion(),
				defaults.updatedAt(),
				defaults.server(),
				defaults.paths(),
				defaults.playout(),
				defaults.cache(),
				defaults.programming(),
				defaults.providers(),
				security,
				defaults.features());
	}
}
