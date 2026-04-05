package com.seedshiftradio.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.radio.QueueItemEntity;

@Service
public class AssetService {

	private final RadioSettingsStore settingsStore;
	private final ProviderRegistry providerRegistry;
	private final TtsProvider ttsProvider;
	private final GeneratedAssetService generatedAssetService;
	private final ProviderJobService providerJobService;
	private final PlaceholderAudioFactory placeholderAudioFactory;

	public AssetService(
			RadioSettingsStore settingsStore,
			ProviderRegistry providerRegistry,
			TtsProvider ttsProvider,
			GeneratedAssetService generatedAssetService,
			ProviderJobService providerJobService,
			PlaceholderAudioFactory placeholderAudioFactory) {
		this.settingsStore = settingsStore;
		this.providerRegistry = providerRegistry;
		this.ttsProvider = ttsProvider;
		this.generatedAssetService = generatedAssetService;
		this.providerJobService = providerJobService;
		this.placeholderAudioFactory = placeholderAudioFactory;
	}

	@Transactional
	public void ensureQueueAudioAsset(QueueItemEntity item) {
		if (item.getAssetId() != null && !item.getAssetId().isBlank()) {
			return;
		}
		ProviderRegistry.ResolvedProvider provider = resolveTtsProvider();
		ProviderJobEntity providerJob = providerJobService.createQueuedJob(
				resolveJobType(item),
				resolveProviderType(item),
				provider.providerKey(),
				item.getId(),
				item.getCorrelationId());
		providerJobService.markRunning(providerJob.getId(), provider.providerKey(), "placeholder-" + item.getId());
		TtsProvider.SynthesizedAudio synthesizedAudio = ttsProvider.synthesize(provider, item);
		GeneratedAssetEntity asset = generatedAssetService.createAudioAsset(
				synthesizedAudio.audioBytes(),
				synthesizedAudio.providerFingerprint(),
				item.getId(),
				providerJob.getId(),
				synthesizedAudio.metadata());
		providerJobService.markSucceeded(providerJob.getId());
		item.setAssetId(asset.getId());
		item.setAssetUrl("/api/assets/audio/" + asset.getId() + ".wav");
	}

	public byte[] loadAudio(String assetId) {
		SettingsDocument settings = settingsStore.load();
		Path legacyAssetPath = generatedAssetService.resolveLegacyAudioPath(assetId);
		Path audioRoot = legacyAssetPath.getParent();
		if (!legacyAssetPath.startsWith(audioRoot)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "assetId が不正です。", Map.of("assetId", assetId));
		}
		Path assetPath = generatedAssetService.resolveAudioAssetPath(assetId).orElse(legacyAssetPath);
		if (Files.exists(assetPath)) {
			byte[] bytes = readBytes(assetPath);
			generatedAssetService.touchAsset(assetId);
			return bytes;
		}
		if (Boolean.TRUE.equals(settings.features().streaming().placeholderEnabled())) {
			return placeholderAudioFactory.createSilentWav(1_000);
		}
		throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "指定された audio asset が見つかりません。", Map.of("assetId", assetId));
	}

	private byte[] readBytes(Path assetPath) {
		try {
			return Files.readAllBytes(assetPath);
		} catch (IOException exception) {
			throw new ApiException(
					HttpStatus.INTERNAL_SERVER_ERROR,
					"INTERNAL_ERROR",
					"音声 asset の読み込みに失敗しました。",
					Map.of("assetPath", assetPath.toString()));
		}
	}

	private ProviderJobType resolveJobType(QueueItemEntity item) {
		return switch (item.getSegmentType()) {
			case MUSIC_AI, MUSIC_LOCAL -> ProviderJobType.MUSIC_GEN;
			default -> ProviderJobType.TTS_GEN;
		};
	}

	private ProviderType resolveProviderType(QueueItemEntity item) {
		return switch (item.getSegmentType()) {
			case MUSIC_AI, MUSIC_LOCAL -> ProviderType.MUSIC;
			default -> ProviderType.TTS;
		};
	}

	private ProviderRegistry.ResolvedProvider resolveTtsProvider() {
		List<ProviderRegistry.ResolvedProvider> providers = providerRegistry.resolveChain(ProviderType.TTS);
		if (!providers.isEmpty()) {
			return providers.getFirst();
		}
		return new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS,
				"tts",
				"seedshift-placeholder",
				"http://127.0.0.1",
				"/health",
				5_000,
				List.of("TTS_GEN"),
				true);
	}
}
