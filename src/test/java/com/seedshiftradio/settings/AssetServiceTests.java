package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.radio.QueueItemEntity;

@ExtendWith(MockitoExtension.class)
class AssetServiceTests {

	@TempDir
	Path tempDir;

	@Mock
	RadioSettingsStore settingsStore;

	@Mock
	ProviderRegistry providerRegistry;

	@Mock
	TtsProvider ttsProvider;

	@Mock
	GeneratedAssetService generatedAssetService;

	@Mock
	ProviderJobService providerJobService;

	@Mock
	PlaceholderAudioFactory placeholderAudioFactory;

	AssetService assetService;

	@BeforeEach
	void setUp() {
		assetService = new AssetService(
				settingsStore,
				providerRegistry,
				ttsProvider,
				generatedAssetService,
				providerJobService,
				placeholderAudioFactory);
	}

	@Test
	void loadAudioTouchesGeneratedAssetWhenFileExists() throws Exception {
		when(settingsStore.load()).thenReturn(settingsDocument());
		Path assetPath = tempDir.resolve("data").resolve("assets").resolve("audio").resolve("asset-1.wav");
		Files.createDirectories(assetPath.getParent());
		byte[] bytes = "asset-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Files.write(assetPath, bytes);
		when(generatedAssetService.resolveLegacyAudioPath("asset-1")).thenReturn(assetPath);
		when(generatedAssetService.resolveAudioAssetPath("asset-1")).thenReturn(Optional.of(assetPath));

		byte[] loaded = assetService.loadAudio("asset-1");

		assertArrayEquals(bytes, loaded);
		verify(generatedAssetService).touchAsset("asset-1");
	}

	private SettingsDocument settingsDocument() {
		return new SettingsDocument(
				1,
				"2026-04",
				Instant.parse("2026-03-20T09:00:00Z"),
				SettingsDocument.ServerSettings.defaults(),
				new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("data").resolve("library").resolve("music").toString()),
				SettingsDocument.PlayoutSettings.defaults(),
				SettingsDocument.CacheSettings.defaults(),
				SettingsDocument.ProgrammingSettings.defaults(),
				SettingsDocument.ProviderCatalog.defaults(),
				SettingsDocument.SecuritySettings.defaults(),
				SettingsDocument.FeatureSettings.defaults())
				.normalize();
	}
}
