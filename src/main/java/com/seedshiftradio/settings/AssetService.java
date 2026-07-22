package com.seedshiftradio.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.radio.QueueItemEntity;

@Service
public class AssetService {

	private static final List<String> LOCAL_MUSIC_EXTENSIONS = List.of(".wav", ".wave");

	private final RadioSettingsStore settingsStore;
	private final GeneratedAssetService generatedAssetService;
	private final PlaceholderAudioFactory placeholderAudioFactory;
	private final SpeechAssetGenerationService speechAssetGenerationService;

	public AssetService(
			RadioSettingsStore settingsStore,
			GeneratedAssetService generatedAssetService,
			PlaceholderAudioFactory placeholderAudioFactory,
			SpeechAssetGenerationService speechAssetGenerationService) {
		this.settingsStore = settingsStore;
		this.generatedAssetService = generatedAssetService;
		this.placeholderAudioFactory = placeholderAudioFactory;
		this.speechAssetGenerationService = speechAssetGenerationService;
	}

	@Transactional
	public void ensureQueueAudioAsset(QueueItemEntity item) {
		if (item.getAssetId() != null && !item.getAssetId().isBlank()) {
			return;
		}
		if (item.getSegmentType() == SegmentType.MUSIC_LOCAL) {
			ensureLocalMusicAsset(item);
			return;
		}
		speechAssetGenerationService.ensureAudioAsset(item);
	}

	private void ensureLocalMusicAsset(QueueItemEntity item) {
		MusicFailureFallback fallback = resolveLocalMusicFallback(item, "MUSIC_LOCAL_SELECTED")
				.orElseGet(() -> createLocalMusicPlaceholderAsset(item));
		item.setAssetId(fallback.assetId());
		item.setAssetUrl(fallback.assetUrl());
		item.setTitle(fallback.title());
		item.setContentOrigin(fallback.contentOrigin());
	}

	@Transactional
	public MusicFailureFallback prepareMusicFailureFallback(QueueItemEntity item, String errorCode) {
		return resolveLocalMusicFallback(item, errorCode)
				.orElseGet(() -> createFallbackJingleAsset(item, errorCode));
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

	private Optional<MusicFailureFallback> resolveLocalMusicFallback(QueueItemEntity item, String errorCode) {
		Optional<Path> candidate = findFirstLocalMusicFile();
		if (candidate.isEmpty()) {
			return Optional.empty();
		}
		try {
			Path assetPath = candidate.orElseThrow().toAbsolutePath().normalize();
			GeneratedAssetEntity asset = generatedAssetService.registerExistingAsset(
					GeneratedAssetType.MUSIC,
					assetPath,
					"server:music-library",
					item.getId(),
					null,
					Map.of(
							"archiveEligible", false,
							"fallbackErrorCode", errorCode,
							"fallbackKind", "MUSIC_LOCAL",
							"sourceFile", assetPath.getFileName() == null ? "unknown" : assetPath.getFileName().toString()));
			String title = localMusicTitle(assetPath);
			return Optional.of(new MusicFailureFallback(
					SegmentType.MUSIC_LOCAL,
					title,
					asset.getId(),
					"/api/assets/audio/" + asset.getId() + ".wav",
					"MUSIC_LOCAL_FALLBACK"));
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private MusicFailureFallback createFallbackJingleAsset(QueueItemEntity item, String errorCode) {
		int durationMs = item.getDurationMs() == null || item.getDurationMs() < 1
				? 15_000
				: item.getDurationMs();
		GeneratedAssetEntity asset = generatedAssetService.createAudioAsset(
				placeholderAudioFactory.createSilentWav(durationMs),
				"server:music-fallback",
				item.getId(),
				null,
				Map.of(
						"archiveEligible", false,
						"fallbackErrorCode", errorCode,
						"fallbackKind", "JINGLE"));
		return new MusicFailureFallback(
				SegmentType.JINGLE,
				"フォールバックジングル",
				asset.getId(),
				"/api/assets/audio/" + asset.getId() + ".wav",
				"JINGLE_FALLBACK");
	}

	private MusicFailureFallback createLocalMusicPlaceholderAsset(QueueItemEntity item) {
		int durationMs = item.getDurationMs() == null || item.getDurationMs() < 1
				? 30_000
				: item.getDurationMs();
		GeneratedAssetEntity asset = generatedAssetService.createAudioAsset(
				placeholderAudioFactory.createSilentWav(durationMs),
				"server:music-local-placeholder",
				item.getId(),
				null,
				Map.of(
						"archiveEligible", false,
						"fallbackKind", "MUSIC_LOCAL_PLACEHOLDER"));
		String title = item.getTitle() == null || item.getTitle().isBlank()
				? "ローカルBGM"
				: item.getTitle();
		return new MusicFailureFallback(
				SegmentType.MUSIC_LOCAL,
				title,
				asset.getId(),
				"/api/assets/audio/" + asset.getId() + ".wav",
				"MUSIC_LOCAL_PLACEHOLDER");
	}

	private Optional<Path> findFirstLocalMusicFile() {
		Path musicLibrary = Path.of(settingsStore.load().paths().musicLibrary()).toAbsolutePath().normalize();
		if (!Files.isDirectory(musicLibrary)) {
			return Optional.empty();
		}
		try (Stream<Path> paths = Files.walk(musicLibrary)) {
			return paths.filter(Files::isRegularFile)
					.map(Path::toAbsolutePath)
					.map(Path::normalize)
					.filter(this::isSupportedLocalMusicFile)
					.sorted(Comparator.comparing(Path::toString))
					.findFirst();
		} catch (IOException | RuntimeException exception) {
			return Optional.empty();
		}
	}

	private boolean isSupportedLocalMusicFile(Path path) {
		String fileName = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		return LOCAL_MUSIC_EXTENSIONS.stream().anyMatch(fileName::endsWith);
	}

	private String localMusicTitle(Path path) {
		String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
		int extensionIndex = fileName.lastIndexOf('.');
		String baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
		if (baseName.isBlank()) {
			return "ローカルBGM";
		}
		return "ローカルBGM: " + baseName;
	}

	public record MusicFailureFallback(
			SegmentType segmentType,
			String title,
			String assetId,
			String assetUrl,
			String contentOrigin) {
	}
}
