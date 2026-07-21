package com.seedshiftradio.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.radio.ScriptGenerationService;
import com.seedshiftradio.radio.SpeechDirectiveResponse;

@Service
public class AssetService {

	private static final List<String> LOCAL_MUSIC_EXTENSIONS = List.of(".wav", ".wave");

	private final RadioSettingsStore settingsStore;
	private final ProviderRegistry providerRegistry;
	private final TtsProvider ttsProvider;
	private final ScriptGenerationService scriptGenerationService;
	private final GeneratedAssetService generatedAssetService;
	private final ProviderJobService providerJobService;
	private final PlaceholderAudioFactory placeholderAudioFactory;
	private final TtsRuntimeProfileResolver ttsRuntimeProfileResolver;

	public AssetService(
			RadioSettingsStore settingsStore,
			ProviderRegistry providerRegistry,
			TtsProvider ttsProvider,
			ScriptGenerationService scriptGenerationService,
			GeneratedAssetService generatedAssetService,
			ProviderJobService providerJobService,
			PlaceholderAudioFactory placeholderAudioFactory,
			TtsRuntimeProfileResolver ttsRuntimeProfileResolver) {
		this.settingsStore = settingsStore;
		this.providerRegistry = providerRegistry;
		this.ttsProvider = ttsProvider;
		this.scriptGenerationService = scriptGenerationService;
		this.generatedAssetService = generatedAssetService;
		this.providerJobService = providerJobService;
		this.placeholderAudioFactory = placeholderAudioFactory;
		this.ttsRuntimeProfileResolver = ttsRuntimeProfileResolver;
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
		SpeechDirectiveResponse directive = toSpeechDirective(item, scriptGenerationService.ensureScriptAsset(item));
		TtsRuntimeProfile runtimeProfile;
		try {
			runtimeProfile = ttsRuntimeProfileResolver.resolve(item).orElse(null);
		} catch (TtsSynthesisException exception) {
			createSafeTtsFallbackAsset(item, directive, exception);
			return;
		}
		TtsSynthesisException lastFailure = null;
		List<ProviderRegistry.ResolvedProvider> ttsProviders;
		try {
			ttsProviders = resolveTtsProviders(runtimeProfile);
		} catch (TtsSynthesisException exception) {
			createPlaceholderTtsAsset(item, directive, exception);
			return;
		}
		for (ProviderRegistry.ResolvedProvider provider : ttsProviders) {
			boolean preferredProfileProvider = runtimeProfile != null
					&& runtimeProfile.providerKey() != null
					&& runtimeProfile.providerKey().equals(provider.providerKey());
			if (preferredProfileProvider && !isCompatible(runtimeProfile, provider)) {
				lastFailure = new TtsSynthesisException(
						com.seedshiftradio.domain.ProviderErrorCode.PROVIDER_REJECTED,
						"TTS 音声プロファイルと Provider adapter が一致しません。");
				continue;
			}
			TtsRuntimeProfile effectiveRuntimeProfile = isCompatible(runtimeProfile, provider)
					? runtimeProfile
					: null;
			ProviderJobEntity providerJob = createTtsProviderJob(item, provider);
			try {
				providerJobService.markRunning(providerJob.getId(), provider.providerKey(), "tts-" + item.getId() + "-" + provider.providerKey());
				TtsProvider.SynthesizedAudio synthesizedAudio = ttsProvider.synthesize(provider, item, directive, effectiveRuntimeProfile);
				GeneratedAssetEntity asset = createTtsAudioAsset(item, providerJob, synthesizedAudio, lastFailure);
				providerJobService.markSucceeded(providerJob.getId());
				item.setAssetId(asset.getId());
				item.setAssetUrl("/api/assets/audio/" + asset.getId() + ".wav");
				return;
			} catch (TtsSynthesisException exception) {
				lastFailure = exception;
				providerJobService.markFailed(providerJob.getId(), exception.providerErrorCode());
				if (!ProviderErrorClassifier.fallbackAllowed(ProviderType.TTS, exception.providerErrorCode())) {
					break;
				}
			}
		}
		if (placeholderEnabled()) {
			createPlaceholderTtsAsset(item, directive, lastFailure);
			return;
		}
		if (lastFailure != null) {
			throw lastFailure;
		}
		throw new TtsSynthesisException("PROVIDER_BAD_RESPONSE", "TTS provider が設定されていません。");
	}

	private void createSafeTtsFallbackAsset(
			QueueItemEntity item,
			SpeechDirectiveResponse directive,
			TtsSynthesisException profileFailure) {
		TtsSynthesisException lastFailure = profileFailure;
		for (ProviderRegistry.ResolvedProvider provider : providerRegistry.resolveChain(ProviderType.TTS)) {
			if (!isVoicevox(provider)) {
				continue;
			}
			ProviderJobEntity providerJob = createTtsProviderJob(item, provider);
			try {
				providerJobService.markRunning(providerJob.getId(), provider.providerKey(), "tts-" + item.getId() + "-" + provider.providerKey());
				TtsProvider.SynthesizedAudio synthesizedAudio = ttsProvider.synthesize(provider, item, directive, null);
				GeneratedAssetEntity asset = createTtsAudioAsset(item, providerJob, synthesizedAudio, lastFailure);
				providerJobService.markSucceeded(providerJob.getId());
				item.setAssetId(asset.getId());
				item.setAssetUrl("/api/assets/audio/" + asset.getId() + ".wav");
				return;
			} catch (TtsSynthesisException exception) {
				lastFailure = exception;
				providerJobService.markFailed(providerJob.getId(), exception.providerErrorCode());
				if (!ProviderErrorClassifier.fallbackAllowed(ProviderType.TTS, exception.providerErrorCode())) {
					break;
				}
			}
		}
		createPlaceholderTtsAsset(item, directive, lastFailure);
	}

	private boolean isCompatible(TtsRuntimeProfile runtimeProfile, ProviderRegistry.ResolvedProvider provider) {
		if (runtimeProfile == null || runtimeProfile.engineType() == null) {
			return true;
		}
		return switch (runtimeProfile.engineType().toUpperCase(java.util.Locale.ROOT)) {
			case "IRODORI_TTS", "IRODORI_OPENAI_TTS" -> isIrodori(provider);
			case "VOICEVOX" -> isVoicevox(provider);
			default -> false;
		};
	}

	private boolean isIrodori(ProviderRegistry.ResolvedProvider provider) {
		return "IRODORI_OPENAI_TTS".equalsIgnoreCase(provider.adapter())
				|| provider.capabilities().contains("IRODORI_TTS")
				|| provider.capabilities().contains("OPENAI_AUDIO_SPEECH");
	}

	private boolean isVoicevox(ProviderRegistry.ResolvedProvider provider) {
		return "VOICEVOX".equalsIgnoreCase(provider.adapter())
				|| provider.capabilities().contains("VOICEVOX")
				|| provider.providerKey().toLowerCase(java.util.Locale.ROOT).contains("voicevox");
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

	private SpeechDirectiveResponse toSpeechDirective(QueueItemEntity item, com.seedshiftradio.radio.ScriptDirectiveSnapshot snapshot) {
		return new SpeechDirectiveResponse(
				item.getSpeechDirectiveId() == null ? "sd-" + item.getId() : item.getSpeechDirectiveId(),
				snapshot.text(),
				snapshot.normalizedText(),
				snapshot.pronunciationHints(),
				snapshot.emotion(),
				snapshot.tempo(),
				snapshot.pauseHints(),
				snapshot.personaRef(),
				snapshot.voiceHint(),
				item.getCorrelationId());
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

	private List<ProviderRegistry.ResolvedProvider> resolveTtsProviders(TtsRuntimeProfile runtimeProfile) {
		String preferredProviderKey = runtimeProfile == null ? null : runtimeProfile.providerKey();
		List<ProviderRegistry.ResolvedProvider> providers = providerRegistry.resolveChain(ProviderType.TTS, preferredProviderKey);
		if (preferredProviderKey != null && !preferredProviderKey.isBlank()
				&& providers.stream().noneMatch(provider -> preferredProviderKey.equals(provider.providerKey()))) {
			throw new TtsSynthesisException(
					com.seedshiftradio.domain.ProviderErrorCode.PROVIDER_REJECTED,
					"TTS 音声プロファイルが指定する Provider は利用できません。");
		}
		if (!providers.isEmpty()) {
			return providers;
		}
		return List.of(placeholderTtsProvider());
	}

	private void createPlaceholderTtsAsset(
			QueueItemEntity item,
			SpeechDirectiveResponse directive,
			TtsSynthesisException previousFailure) {
		if (!placeholderEnabled()) {
			throw previousFailure == null
					? new TtsSynthesisException("PROVIDER_BAD_RESPONSE", "TTS provider が設定されていません。")
					: previousFailure;
		}
		ProviderRegistry.ResolvedProvider placeholder = placeholderTtsProvider();
		ProviderJobEntity providerJob = createTtsProviderJob(item, placeholder);
		providerJobService.markRunning(providerJob.getId(), placeholder.providerKey(), "tts-" + item.getId() + "-placeholder");
		TtsProvider.SynthesizedAudio synthesizedAudio = ttsProvider.synthesize(placeholder, item, directive, null);
		GeneratedAssetEntity asset = createTtsAudioAsset(item, providerJob, synthesizedAudio, previousFailure);
		providerJobService.markSucceeded(providerJob.getId());
		item.setAssetId(asset.getId());
		item.setAssetUrl("/api/assets/audio/" + asset.getId() + ".wav");
	}

	private ProviderRegistry.ResolvedProvider placeholderTtsProvider() {
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

	private ProviderJobEntity createTtsProviderJob(QueueItemEntity item, ProviderRegistry.ResolvedProvider provider) {
		return providerJobService.createQueuedJob(
				resolveJobType(item),
				resolveProviderType(item),
				provider.providerKey(),
				item.getId(),
				item.getCorrelationId());
	}

	private GeneratedAssetEntity createTtsAudioAsset(
			QueueItemEntity item,
			ProviderJobEntity providerJob,
			TtsProvider.SynthesizedAudio synthesizedAudio,
			TtsSynthesisException previousFailure) {
		return generatedAssetService.createAudioAsset(
				synthesizedAudio.audioBytes(),
				synthesizedAudio.providerFingerprint(),
				item.getId(),
				providerJob.getId(),
				ttsMetadata(synthesizedAudio.metadata(), previousFailure));
	}

	private Map<String, Object> ttsMetadata(Map<String, Object> metadata, TtsSynthesisException previousFailure) {
		Map<String, Object> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
		if (previousFailure != null) {
			merged.put("fallbackProviderUsed", true);
			merged.put("fallbackErrorCode", previousFailure.errorCode());
		}
		return merged;
	}

	private boolean placeholderEnabled() {
		return Boolean.TRUE.equals(settingsStore.load().features().streaming().placeholderEnabled());
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
