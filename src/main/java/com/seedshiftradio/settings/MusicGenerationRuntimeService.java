package com.seedshiftradio.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.settings.GpuExecutionCoordinator.ExecutionOrigin;
import com.seedshiftradio.settings.GpuExecutionCoordinator.InferenceWorkload;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport.PreGenerationProfile;
import com.seedshiftradio.programming.StationProgrammingPolicyRepository;
import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.station.StationRepository;

@Service
public class MusicGenerationRuntimeService {

	private static final Set<String> NO_REUSE_SCOPES = Set.of("DISABLED", "ARCHIVE_ONLY");

	private final MusicGenWorkerGateway musicGenWorkerGateway;
	private final ProviderJobService providerJobService;
	private final GeneratedAssetService generatedAssetService;
	private final RadioSettingsStore settingsStore;
	private final StationRepository stationRepository;
	private final StationProgrammingPolicyRepository programmingPolicyRepository;
	private final GpuExecutionCoordinator gpuExecutionCoordinator;

	@Autowired
	public MusicGenerationRuntimeService(
			MusicGenWorkerGateway musicGenWorkerGateway,
			ProviderJobService providerJobService,
			GeneratedAssetService generatedAssetService,
			RadioSettingsStore settingsStore,
			StationRepository stationRepository,
			StationProgrammingPolicyRepository programmingPolicyRepository,
			GpuExecutionCoordinator gpuExecutionCoordinator) {
		this.musicGenWorkerGateway = musicGenWorkerGateway;
		this.providerJobService = providerJobService;
		this.generatedAssetService = generatedAssetService;
		this.settingsStore = settingsStore;
		this.stationRepository = stationRepository;
		this.programmingPolicyRepository = programmingPolicyRepository;
		this.gpuExecutionCoordinator = gpuExecutionCoordinator;
	}

	MusicGenerationRuntimeService(
			MusicGenWorkerGateway musicGenWorkerGateway,
			ProviderJobService providerJobService,
			GeneratedAssetService generatedAssetService,
			RadioSettingsStore settingsStore,
			StationRepository stationRepository,
			StationProgrammingPolicyRepository programmingPolicyRepository) {
		this(
				musicGenWorkerGateway,
				providerJobService,
				generatedAssetService,
				settingsStore,
				stationRepository,
				programmingPolicyRepository,
				null);
	}

	public GeneratedMusicAsset generate(String stationId, QueueItemEntity item) {
		return generate(stationId, item, ExecutionOrigin.AUTOMATIC);
	}

	public GeneratedMusicAsset generate(
			String stationId,
			QueueItemEntity item,
			ExecutionOrigin origin) {
		List<MusicGenWorkerGateway.ResolvedMusicProvider> providers = musicGenWorkerGateway.resolveProviders();
		MusicGenerationRequest request = buildRequest(stationId, item);
		String reuseScope = settingsStore.load().cache().musicReuseScope();
		ProviderJobEntity providerJob = providerJobService.createQueuedJob(
				ProviderJobType.MUSIC_GEN,
				ProviderType.MUSIC,
				providers.getFirst().providerKey(),
				item.getId(),
				item.getCorrelationId());
		try {
			CachedAssetHit cachedAssetHit = findReusableAsset(providers, request, item, stationId, reuseScope);
			GeneratedAssetEntity reusableAsset = cachedAssetHit == null ? null : cachedAssetHit.asset();
			if (reusableAsset != null) {
				providerJobService.markRunning(providerJob.getId(), cachedAssetHit.provider().providerKey(), "cache-hit:" + reusableAsset.getId());
				GeneratedAssetEntity asset = generatedAssetService.cloneAssetForQueue(
						reusableAsset,
						item.getId(),
						providerJob.getId(),
						cachedAssetHit.cacheKey(),
						buildCacheHitMetadata(stationId, item, cachedAssetHit.provider(), providerJob, reusableAsset));
				providerJobService.markSucceeded(providerJob.getId());
				return new GeneratedMusicAsset(
						asset.getId(),
						"/api/assets/audio/" + asset.getId() + ".wav",
						providerJob.getId(),
						null,
						"CACHE_REUSED",
						resolveDurationSec(asset.getMetadata(), request.durationSeconds()));
			}
			if (gpuExecutionCoordinator == null) {
				return generateWithProvider(stationId, item, providers, request, reuseScope, providerJob);
			}
			return gpuExecutionCoordinator.execute(
					origin == null ? ExecutionOrigin.AUTOMATIC : origin,
					InferenceWorkload.MUSIC,
					providers.getFirst().providerKey(),
					() -> generateWithProvider(stationId, item, providers, request, reuseScope, providerJob));
		} catch (MusicGenWorkerException exception) {
			providerJobService.markFailed(providerJob.getId(), exception.providerErrorCode());
			throw exception;
		} catch (ProviderRuntimeException exception) {
			providerJobService.markFailed(providerJob.getId(), exception.providerErrorCode());
			throw new MusicGenWorkerException(exception.errorCode(), exception.getMessage(), exception);
		} catch (RuntimeException exception) {
			providerJobService.markFailed(providerJob.getId(), ProviderErrorCode.PROVIDER_BAD_RESPONSE);
			throw exception;
		}
	}

	private GeneratedMusicAsset generateWithProvider(
			String stationId,
			QueueItemEntity item,
			List<MusicGenWorkerGateway.ResolvedMusicProvider> providers,
			MusicGenerationRequest request,
			String reuseScope,
			ProviderJobEntity providerJob) {
		MusicGenWorkerGateway.SubmittedMusicJob submittedJob = musicGenWorkerGateway.submitWithFallback(providers, request);
		providerJobService.markRunning(providerJob.getId(), submittedJob.provider().providerKey(), submittedJob.jobId());
		MusicGenWorkerGateway.MusicJobStatus completedJob = musicGenWorkerGateway.awaitCompletion(
				submittedJob.provider(),
				submittedJob.jobId());
		Path assetPath = Path.of(completedJob.assetPath());
		Integer actualDurationSec = resolveAssetDurationSec(
				assetPath,
				completedJob.durationSec(),
				request.durationSeconds());
		String cacheKey = buildCacheKey(submittedJob.provider(), request, item, reuseScope);
		GeneratedAssetEntity asset = generatedAssetService.registerExistingAsset(
				GeneratedAssetType.MUSIC,
				assetPath,
				completedJob.providerFingerprint() == null || completedJob.providerFingerprint().isBlank()
						? submittedJob.provider().providerKey()
						: completedJob.providerFingerprint(),
				item.getId(),
				providerJob.getId(),
				cacheKey,
				buildGeneratedMetadata(stationId, item, request, submittedJob.provider(), providerJob, completedJob, actualDurationSec, cacheKey));
		providerJobService.markSucceeded(providerJob.getId());
		return new GeneratedMusicAsset(
				asset.getId(),
				"/api/assets/audio/" + asset.getId() + ".wav",
				providerJob.getId(),
				submittedJob.jobId(),
				"LIVE_GEN",
				actualDurationSec);
	}

	public boolean discardGeneratedAsset(String assetId) {
		return generatedAssetService.discardUnlinkedGeneratedAsset(assetId);
	}

	private CachedAssetHit findReusableAsset(
			List<MusicGenWorkerGateway.ResolvedMusicProvider> providers,
			MusicGenerationRequest request,
			QueueItemEntity item,
			String stationId,
			String reuseScope) {
		if (NO_REUSE_SCOPES.contains(reuseScope) || !preferCacheReuse(stationId)) {
			return null;
		}
		for (MusicGenWorkerGateway.ResolvedMusicProvider provider : providers) {
			String cacheKey = buildCacheKey(provider, request, item, reuseScope);
			GeneratedAssetEntity reusableAsset = generatedAssetService.findReusableAsset(GeneratedAssetType.MUSIC, cacheKey).orElse(null);
			if (reusableAsset != null) {
				return new CachedAssetHit(provider, cacheKey, reusableAsset);
			}
		}
		return null;
	}

	private boolean preferCacheReuse(String stationId) {
		PreGenerationProfile profile = stationId == null || stationId.isBlank()
				? ProgrammingPolicyProfileSupport.defaultPreGenerationProfile()
				: programmingPolicyRepository.findByStationId(stationId)
						.map(policy -> ProgrammingPolicyProfileSupport.toPreGenerationProfile(policy.getPreGenerationPolicy()))
						.orElseGet(ProgrammingPolicyProfileSupport::defaultPreGenerationProfile);
		return Boolean.TRUE.equals(profile.preferCacheReuse());
	}

	private MusicGenerationRequest buildRequest(String stationId, QueueItemEntity item) {
		String genre = resolveGenre(stationId);
		List<String> mood = resolveMood(item.getSlotRole());
		Integer durationSec = normalizeDurationSec(item.getDurationMs());
		String prompt = buildPrompt(genre, mood, item, durationSec);
		String lyrics = buildSafeJapaneseLyrics(genre, mood, item, durationSec);
		return new MusicGenerationRequest(
				item.getCorrelationId() + ":" + item.getId(),
				stationId,
				"radio",
				"JAPANESE_SONG",
				prompt,
				lyrics,
				"ja",
				durationSec,
				resolveBpm(item.getSlotRole()),
				"",
				"4",
				resolveSeed(item),
				null,
				"wav");
	}

	private Map<String, Object> buildGeneratedMetadata(
			String stationId,
			QueueItemEntity item,
			MusicGenerationRequest request,
			MusicGenWorkerGateway.ResolvedMusicProvider provider,
			ProviderJobEntity providerJob,
			MusicGenWorkerGateway.MusicJobStatus completedJob,
			Integer actualDurationSec,
			String cacheKey) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("stationId", stationId);
		metadata.put("queueItemId", item.getId());
		metadata.put("providerKey", provider.providerKey());
		metadata.put("providerJobId", providerJob.getId());
		metadata.put("workerJobId", completedJob.jobId());
		metadata.put("cacheKey", cacheKey);
		metadata.put("cacheHit", false);
		metadata.put("segmentType", item.getSegmentType().name());
		metadata.put("slotRole", item.getSlotRole().name());
		metadata.put("duration", actualDurationSec);
		metadata.put("workerDuration", completedJob.durationSec());
		metadata.put("modelProfileId", request.modelProfileId());
		metadata.put("lyricsLanguage", request.lyricsLanguage());
		metadata.put("promptHash", completedJob.promptHash() == null || completedJob.promptHash().isBlank() ? sha256(request.prompt()) : completedJob.promptHash());
		metadata.put("lyricsHash", completedJob.lyricsHash() == null || completedJob.lyricsHash().isBlank() ? sha256(request.lyrics()) : completedJob.lyricsHash());
		if (completedJob.model() != null && !completedJob.model().isBlank()) {
			metadata.put("model", completedJob.model());
		}
		if (completedJob.lmModel() != null && !completedJob.lmModel().isBlank()) {
			metadata.put("lmModel", completedJob.lmModel());
		}
		if (completedJob.seed() != null && !completedJob.seed().isBlank()) {
			metadata.put("seed", completedJob.seed());
		}
		return metadata;
	}

	private Map<String, Object> buildCacheHitMetadata(
			String stationId,
			QueueItemEntity item,
			MusicGenWorkerGateway.ResolvedMusicProvider provider,
			ProviderJobEntity providerJob,
			GeneratedAssetEntity reusableAsset) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("stationId", stationId);
		metadata.put("queueItemId", item.getId());
		metadata.put("providerKey", provider.providerKey());
		metadata.put("providerJobId", providerJob.getId());
		metadata.put("cacheKey", reusableAsset.getCacheKey());
		metadata.put("cacheHit", true);
		metadata.put("cacheSourceAssetId", reusableAsset.getId());
		metadata.put("segmentType", item.getSegmentType().name());
		metadata.put("slotRole", item.getSlotRole().name());
		return metadata;
	}

	private String buildPrompt(String genre, List<String> mood, QueueItemEntity item, int durationSec) {
		int outroStartSec = Math.max(10, durationSec - 20);
		return "Japanese original radio song, "
				+ "genre=" + sanitizePromptToken(genre)
				+ ", mood=" + String.join(",", mood.stream().map(this::sanitizePromptToken).toList())
				+ ", clear Japanese vocal, no artist imitation, no copyrighted song reference, "
				+ "fits a local AI radio music break titled " + sanitizePromptToken(item.getTitle()) + ", "
				+ "complete the full song form within exactly " + durationSec + " seconds, "
				+ "begin the outro no later than " + outroStartSec + " seconds, "
				+ "resolve to a clear final tonic cadence, hold the ending, and use the final 6 seconds for a natural fade, "
				+ "never cut off a vocal, phrase, or sustained note at the end";
	}

	private String buildSafeJapaneseLyrics(String genre, List<String> mood, QueueItemEntity item, int durationSec) {
		String tone = mood.contains("bright") || mood.contains("intro") ? "新しい朝" : mood.contains("closing") ? "静かな夜" : "ゆるやかな時間";
		String scene = sanitizeJapaneseText(item.getTitle());
		if (scene.isBlank()) {
			scene = sanitizeJapaneseText(genre);
		}
		List<String> lyrics = new java.util.ArrayList<>(List.of(
				"[Verse]",
				"窓辺をすべる " + tone + "の風",
				"名前のないリズムが 胸でほどけていく",
				"[Chorus]",
				"この街の音に 耳を澄ませば",
				"小さな願いが メロディーになる"));
		if (durationSec >= 90) {
			lyrics.addAll(List.of(
					"[Verse 2]",
					"移ろう景色を やさしく照らして",
					"重なる足音が 明日へ続いていく",
					"[Chorus]",
					"この街の音に 耳を澄ませば",
					"小さな願いが メロディーになる"));
		}
		lyrics.addAll(List.of(
				"[Bridge]",
				scene + "を越えて まだ見ぬ方へ",
				"[Outro]",
				"また次の曲で 会えますように",
				"[End: hold final chord and fade out completely]"));
		return String.join("\n", lyrics);
	}

	private Integer resolveDurationSec(Map<String, Object> metadata, Integer fallbackDurationSec) {
		Object value = metadata == null ? null : metadata.get("duration");
		if (value instanceof Number number) {
			return positiveDurationOrFallback(number.intValue(), fallbackDurationSec);
		}
		if (value instanceof String text) {
			try {
				return positiveDurationOrFallback(Integer.valueOf(text), fallbackDurationSec);
			} catch (NumberFormatException ignored) {
				// 古い asset の不正な metadata は計画尺へフォールバックする。
			}
		}
		return positiveDurationOrFallback(null, fallbackDurationSec);
	}

	private Integer resolveAssetDurationSec(Path assetPath, Integer workerDurationSec, Integer fallbackDurationSec) {
		try (AudioInputStream audio = AudioSystem.getAudioInputStream(assetPath.toFile())) {
			long frameLength = audio.getFrameLength();
			float frameRate = audio.getFormat().getFrameRate();
			if (frameLength > 0 && frameRate > 0) {
				long roundedDurationSec = Math.round(frameLength / (double) frameRate);
				if (roundedDurationSec > 0 && roundedDurationSec <= Integer.MAX_VALUE) {
					return (int) roundedDurationSec;
				}
			}
		} catch (IOException | UnsupportedAudioFileException ignored) {
			// 読み取れない旧 asset は worker 応答または計画尺へフォールバックする。
		}
		return positiveDurationOrFallback(workerDurationSec, fallbackDurationSec);
	}

	private Integer positiveDurationOrFallback(Integer durationSec, Integer fallbackDurationSec) {
		if (durationSec != null && durationSec > 0) {
			return durationSec;
		}
		return fallbackDurationSec != null && fallbackDurationSec > 0 ? fallbackDurationSec : 30;
	}

	private Integer resolveBpm(SlotRole slotRole) {
		return switch (slotRole) {
			case OPENING -> 124;
			case TOPIC -> 108;
			case LETTER -> 92;
			case MUSIC_BREAK -> 112;
			case ENDING -> 88;
		};
	}

	private String sanitizePromptToken(String value) {
		return value == null ? "" : value.replaceAll("[\r\n\t]+", " ").replaceAll("[^A-Za-z0-9ぁ-んァ-ン一-龠ー _,-]", " ").trim();
	}

	private String sanitizeJapaneseText(String value) {
		String sanitized = value == null ? "" : value.replaceAll("[\r\n\t]+", " ").replaceAll("[<>\\[\\]{}]", " ").trim();
		return sanitized.length() > 16 ? sanitized.substring(0, 16) : sanitized;
	}

	private String resolveGenre(String stationId) {
		return stationRepository.findById(stationId)
				.map(station -> station.getGenre().toLowerCase(Locale.ROOT))
				.orElse("ambient");
	}

	private List<String> resolveMood(SlotRole slotRole) {
		return switch (slotRole) {
			case OPENING -> List.of("bright", "intro");
			case TOPIC -> List.of("warm", "flow");
			case LETTER -> List.of("gentle", "supportive");
			case MUSIC_BREAK -> List.of("ambient", "calm");
			case ENDING -> List.of("calm", "closing");
		};
	}

	private Integer normalizeDurationSec(Integer durationMs) {
		int safeDurationMs = durationMs == null ? 30_000 : durationMs;
		return Math.max(5, Math.min(120, safeDurationMs / 1_000));
	}

	private Integer resolveSeed(QueueItemEntity item) {
		return Math.floorMod((item.getCorrelationId() + ":" + item.getId()).hashCode(), Integer.MAX_VALUE);
	}

	private String buildCacheKey(
			MusicGenWorkerGateway.ResolvedMusicProvider provider,
			MusicGenerationRequest request,
			QueueItemEntity item,
			String reuseScope) {
		if (NO_REUSE_SCOPES.contains(reuseScope)) {
			return null;
		}
		SettingsDocument.MusicGenerationModelProfile profile = provider.profile(request.modelProfileId());
		MusicGenerationRequest normalizedRequest = request.normalize(profile);
		String scopePartition = switch (reuseScope) {
			case "GLOBAL" -> "global";
			case "SESSION" -> normalize(item.getSessionId());
			case "STATION" -> normalize(normalizedRequest.stationId());
			default -> normalize(normalizedRequest.stationId());
		};
		String raw = String.join(
				"|",
				normalize(provider.providerKey()),
				normalize(provider.baseUrl()),
				normalize(provider.adapter()),
				normalize(provider.resolvedProfileId(request.modelProfileId())),
				normalize(profile.model()),
				normalize(profile.lmModel()),
				normalize(profile.lyricsTransliterationMode()),
				normalize(reuseScope),
				scopePartition,
				normalize(normalizedRequest.stationId()),
				normalize(item.getProgramBlockId()),
				normalize(item.getProgramSlotId()),
				normalize(normalizedRequest.purpose()),
				normalize(normalizedRequest.mode()),
				normalize(normalizedRequest.lyricsLanguage()),
				sha256(normalizedRequest.prompt()),
				sha256(normalizedRequest.lyrics()),
				normalize(normalizedRequest.keyScale()),
				normalize(normalizedRequest.timeSignature()),
				String.valueOf(normalizedRequest.bpm() == null ? 0 : normalizedRequest.bpm()),
				String.valueOf(normalizedRequest.durationSeconds()),
				String.valueOf(normalizedRequest.seed() == null ? 0 : normalizedRequest.seed()),
				normalize(normalizedRequest.outputFormat()));
		return sha256(raw);
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(bytes.length * 2);
			for (byte current : bytes) {
				builder.append(String.format("%02x", current));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 が利用できません。", exception);
		}
	}

	public record GeneratedMusicAsset(
			String assetId,
			String assetUrl,
			String providerJobId,
			String workerJobId,
			String contentOrigin,
			Integer durationSec) {
	}

	private record CachedAssetHit(
			MusicGenWorkerGateway.ResolvedMusicProvider provider,
			String cacheKey,
			GeneratedAssetEntity asset) {
	}
}
