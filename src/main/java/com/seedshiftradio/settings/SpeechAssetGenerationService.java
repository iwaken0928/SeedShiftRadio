package com.seedshiftradio.settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.radio.ScriptDirectiveSnapshot;
import com.seedshiftradio.radio.ScriptGenerationService;
import com.seedshiftradio.radio.SpeechDirectiveResponse;

@Service
public class SpeechAssetGenerationService {

	private static final List<String> NO_REUSE_SCOPES = List.of("DISABLED", "ARCHIVE_ONLY");
	private static final Set<String> SENSITIVE_METADATA_KEYS = Set.of(
			"text",
			"normalizedText",
			"voiceHint",
			"speakerKey",
			"voiceId",
			"referenceVoiceRef",
			"consentPolicyRef",
			"prompt",
			"letterBody",
			"radioName",
			"apiKey",
			"rawResponse",
			"assetPath");

	private final RadioSettingsStore settingsStore;
	private final ProviderRegistry providerRegistry;
	private final TtsProvider ttsProvider;
	private final ScriptGenerationService scriptGenerationService;
	private final GeneratedAssetService generatedAssetService;
	private final ProviderJobService providerJobService;
	private final TtsRuntimeProfileResolver ttsRuntimeProfileResolver;

	public SpeechAssetGenerationService(
			RadioSettingsStore settingsStore,
			ProviderRegistry providerRegistry,
			TtsProvider ttsProvider,
			ScriptGenerationService scriptGenerationService,
			GeneratedAssetService generatedAssetService,
			ProviderJobService providerJobService,
			TtsRuntimeProfileResolver ttsRuntimeProfileResolver) {
		this.settingsStore = settingsStore;
		this.providerRegistry = providerRegistry;
		this.ttsProvider = ttsProvider;
		this.scriptGenerationService = scriptGenerationService;
		this.generatedAssetService = generatedAssetService;
		this.providerJobService = providerJobService;
		this.ttsRuntimeProfileResolver = ttsRuntimeProfileResolver;
	}

	@Transactional
	public void ensureAudioAsset(QueueItemEntity item) {
		ScriptDirectiveSnapshot snapshot = scriptGenerationService.ensureScriptAsset(item);
		GeneratedAssetEntity scriptAsset = generatedAssetService.findLatestScriptAssetForQueueItem(item.getId()).orElse(null);
		SpeechDirectiveResponse directive = toSpeechDirective(item, snapshot);
		TtsRuntimeProfile runtimeProfile;
		try {
			runtimeProfile = ttsRuntimeProfileResolver.resolve(item).orElse(null);
		} catch (TtsSynthesisException exception) {
			createSafeFallbackAsset(item, directive, scriptAsset, exception);
			return;
		}
		TtsSynthesisException lastFailure = null;
		List<ProviderRegistry.ResolvedProvider> providers;
		try {
			providers = resolveProviders(runtimeProfile);
		} catch (TtsSynthesisException exception) {
			createPlaceholderAsset(item, directive, scriptAsset, exception);
			return;
		}
		for (ProviderRegistry.ResolvedProvider provider : providers) {
			boolean preferredProfileProvider = runtimeProfile != null
					&& runtimeProfile.providerKey() != null
					&& runtimeProfile.providerKey().equals(provider.providerKey());
			if (preferredProfileProvider && !isCompatible(runtimeProfile, provider)) {
				lastFailure = new TtsSynthesisException(
						ProviderErrorCode.PROVIDER_REJECTED,
						"TTS 音声プロファイルと Provider adapter が一致しません。");
				continue;
			}
			TtsRuntimeProfile effectiveProfile = isCompatible(runtimeProfile, provider) ? runtimeProfile : null;
			String cacheKey = buildCacheKey(item, directive, provider, effectiveProfile, lastFailure);
			Optional<GeneratedAssetEntity> reusableAsset = findReusableAsset(cacheKey);
			if (reusableAsset.isPresent()) {
				reuseAsset(item, scriptAsset, directive, provider, effectiveProfile, cacheKey, reusableAsset.orElseThrow(), lastFailure);
				return;
			}
			ProviderJobEntity providerJob = createProviderJob(item, provider);
			try {
				providerJobService.markRunning(providerJob.getId(), provider.providerKey(), "tts-" + item.getId() + "-" + provider.providerKey());
				TtsProvider.SynthesizedAudio synthesizedAudio = ttsProvider.synthesize(provider, item, directive, effectiveProfile);
				GeneratedAssetEntity asset = createAudioAsset(
						item, scriptAsset, directive, provider, effectiveProfile, providerJob, synthesizedAudio, cacheKey, lastFailure);
				providerJobService.markSucceeded(providerJob.getId());
				applyAsset(item, asset, "LIVE_GEN");
				return;
			} catch (TtsSynthesisException exception) {
				lastFailure = exception;
				providerJobService.markFailed(providerJob.getId(), exception.providerErrorCode());
				if (!ProviderErrorClassifier.fallbackAllowed(ProviderType.TTS, exception.providerErrorCode())) {
					break;
				}
			} catch (RuntimeException exception) {
				providerJobService.markFailed(providerJob.getId(), ProviderErrorCode.PROVIDER_BAD_RESPONSE);
				throw exception;
			}
		}
		if (placeholderEnabled()) {
			createPlaceholderAsset(item, directive, scriptAsset, lastFailure);
			return;
		}
		if (lastFailure != null) {
			throw lastFailure;
		}
		throw new TtsSynthesisException("PROVIDER_BAD_RESPONSE", "TTS provider が設定されていません。");
	}

	private void createSafeFallbackAsset(
			QueueItemEntity item,
			SpeechDirectiveResponse directive,
			GeneratedAssetEntity scriptAsset,
			TtsSynthesisException profileFailure) {
		TtsSynthesisException lastFailure = profileFailure;
		for (ProviderRegistry.ResolvedProvider provider : providerRegistry.resolveChain(ProviderType.TTS)) {
			if (!isVoicevox(provider)) {
				continue;
			}
			String cacheKey = buildCacheKey(item, directive, provider, null, lastFailure);
			Optional<GeneratedAssetEntity> reusableAsset = findReusableAsset(cacheKey);
			if (reusableAsset.isPresent()) {
				reuseAsset(item, scriptAsset, directive, provider, null, cacheKey, reusableAsset.orElseThrow(), lastFailure);
				return;
			}
			ProviderJobEntity providerJob = createProviderJob(item, provider);
			try {
				providerJobService.markRunning(providerJob.getId(), provider.providerKey(), "tts-" + item.getId() + "-" + provider.providerKey());
				TtsProvider.SynthesizedAudio synthesizedAudio = ttsProvider.synthesize(provider, item, directive, null);
				GeneratedAssetEntity asset = createAudioAsset(
						item, scriptAsset, directive, provider, null, providerJob, synthesizedAudio, cacheKey, lastFailure);
				providerJobService.markSucceeded(providerJob.getId());
				applyAsset(item, asset, "LIVE_GEN");
				return;
			} catch (TtsSynthesisException exception) {
				lastFailure = exception;
				providerJobService.markFailed(providerJob.getId(), exception.providerErrorCode());
				if (!ProviderErrorClassifier.fallbackAllowed(ProviderType.TTS, exception.providerErrorCode())) {
					break;
				}
			} catch (RuntimeException exception) {
				providerJobService.markFailed(providerJob.getId(), ProviderErrorCode.PROVIDER_BAD_RESPONSE);
				throw exception;
			}
		}
		createPlaceholderAsset(item, directive, scriptAsset, lastFailure);
	}

	private void createPlaceholderAsset(
			QueueItemEntity item,
			SpeechDirectiveResponse directive,
			GeneratedAssetEntity scriptAsset,
			TtsSynthesisException previousFailure) {
		if (!placeholderEnabled()) {
			throw previousFailure == null
					? new TtsSynthesisException("PROVIDER_BAD_RESPONSE", "TTS provider が設定されていません。")
					: previousFailure;
		}
		ProviderRegistry.ResolvedProvider provider = placeholderProvider();
		String cacheKey = buildCacheKey(item, directive, provider, null, previousFailure);
		Optional<GeneratedAssetEntity> reusableAsset = findReusableAsset(cacheKey);
		if (reusableAsset.isPresent()) {
			reuseAsset(item, scriptAsset, directive, provider, null, cacheKey, reusableAsset.orElseThrow(), previousFailure);
			return;
		}
		ProviderJobEntity providerJob = createProviderJob(item, provider);
		try {
			providerJobService.markRunning(providerJob.getId(), provider.providerKey(), "tts-" + item.getId() + "-placeholder");
			TtsProvider.SynthesizedAudio synthesizedAudio = ttsProvider.synthesize(provider, item, directive, null);
			GeneratedAssetEntity asset = createAudioAsset(
					item, scriptAsset, directive, provider, null, providerJob, synthesizedAudio, cacheKey, previousFailure);
			providerJobService.markSucceeded(providerJob.getId());
			applyAsset(item, asset, "LIVE_GEN");
		} catch (TtsSynthesisException exception) {
			providerJobService.markFailed(providerJob.getId(), exception.providerErrorCode());
			throw exception;
		} catch (RuntimeException exception) {
			providerJobService.markFailed(providerJob.getId(), ProviderErrorCode.PROVIDER_BAD_RESPONSE);
			throw exception;
		}
	}

	private Optional<GeneratedAssetEntity> findReusableAsset(String cacheKey) {
		return cacheKey == null ? Optional.empty() : generatedAssetService.findReusableAsset(GeneratedAssetType.AUDIO, cacheKey);
	}

	private void reuseAsset(
			QueueItemEntity item,
			GeneratedAssetEntity scriptAsset,
			SpeechDirectiveResponse directive,
			ProviderRegistry.ResolvedProvider provider,
			TtsRuntimeProfile runtimeProfile,
			String cacheKey,
			GeneratedAssetEntity reusableAsset,
			TtsSynthesisException previousFailure) {
		ProviderJobEntity providerJob = createProviderJob(item, provider);
		providerJobService.markRunning(providerJob.getId(), provider.providerKey(), "cache-hit:" + reusableAsset.getId());
		Map<String, Object> metadata = traceMetadata(
				item, scriptAsset, directive, provider, runtimeProfile, providerJob.getId(), cacheKey, previousFailure);
		metadata.put("cacheHit", true);
		metadata.put("sourceAssetId", reusableAsset.getId());
		try {
			GeneratedAssetEntity asset = generatedAssetService.cloneAssetForQueue(
					reusableAsset, item.getId(), providerJob.getId(), cacheKey, metadata);
			providerJobService.markSucceeded(providerJob.getId());
			applyAsset(item, asset, "CACHE_REUSED");
		} catch (RuntimeException exception) {
			providerJobService.markFailed(providerJob.getId(), ProviderErrorCode.PROVIDER_BAD_RESPONSE);
			throw exception;
		}
	}

	private GeneratedAssetEntity createAudioAsset(
			QueueItemEntity item,
			GeneratedAssetEntity scriptAsset,
			SpeechDirectiveResponse directive,
			ProviderRegistry.ResolvedProvider provider,
			TtsRuntimeProfile runtimeProfile,
			ProviderJobEntity providerJob,
			TtsProvider.SynthesizedAudio synthesizedAudio,
			String cacheKey,
			TtsSynthesisException previousFailure) {
		Map<String, Object> metadata = new LinkedHashMap<>(
				synthesizedAudio.metadata() == null ? Map.of() : synthesizedAudio.metadata());
		SENSITIVE_METADATA_KEYS.forEach(metadata::remove);
		metadata.putAll(traceMetadata(
				item, scriptAsset, directive, provider, runtimeProfile, providerJob.getId(), cacheKey, previousFailure));
		metadata.put("cacheHit", false);
		return generatedAssetService.createAudioAsset(
				synthesizedAudio.audioBytes(),
				synthesizedAudio.providerFingerprint(),
				item.getId(),
				providerJob.getId(),
				cacheKey,
				metadata);
	}

	private Map<String, Object> traceMetadata(
			QueueItemEntity item,
			GeneratedAssetEntity scriptAsset,
			SpeechDirectiveResponse directive,
			ProviderRegistry.ResolvedProvider provider,
			TtsRuntimeProfile runtimeProfile,
			String providerJobId,
			String cacheKey,
			TtsSynthesisException previousFailure) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("queueItemId", item.getId());
		metadata.put("providerKey", provider.providerKey());
		metadata.put("adapter", provider.adapter());
		metadata.put("providerJobId", providerJobId);
		metadata.put("cacheKey", cacheKey);
		metadata.put("normalizedTextHash", sha256(directive.normalizedText()));
		metadata.put("voiceHintHash", sha256(directive.voiceHint()));
		metadata.put("pronunciationHintCount", directive.pronunciationHints().size());
		metadata.put("pauseHintCount", directive.pauseHints().size());
		metadata.put("scriptAssetId", scriptAsset == null ? null : scriptAsset.getId());
		metadata.put("scriptProviderJobId", scriptAsset == null ? null : scriptAsset.getProviderJobId());
		if (runtimeProfile != null) {
			metadata.put("voiceProfileId", runtimeProfile.voiceProfileId());
			metadata.put("engineType", runtimeProfile.engineType());
			metadata.put("speakerKeyHash", sha256(runtimeProfile.speakerKey()));
			metadata.put("stylePreset", runtimeProfile.styleKey());
			metadata.put("responseFormat", canonicalValue(runtimeProfile.providerOptions().getOrDefault("responseFormat", "wav")));
			metadata.put("referenceVoiceHash", sha256(runtimeProfile.referenceVoiceRef()));
			metadata.put("consentPolicyHash", sha256(runtimeProfile.consentPolicyRef()));
		}
		if (previousFailure != null) {
			metadata.put("fallbackProviderUsed", true);
			metadata.put("fallbackErrorCode", previousFailure.errorCode());
		}
		return metadata;
	}

	String buildCacheKey(
			QueueItemEntity item,
			SpeechDirectiveResponse directive,
			ProviderRegistry.ResolvedProvider provider,
			TtsRuntimeProfile runtimeProfile,
			TtsSynthesisException previousFailure) {
		if (item.getLetterId() != null && !item.getLetterId().isBlank()) {
			return null;
		}
		String reuseScope = settingsStore.load().cache().ttsReuseScope();
		if (NO_REUSE_SCOPES.contains(reuseScope)) {
			return null;
		}
		String scopePartition = switch (reuseScope) {
			case "GLOBAL" -> "global";
			case "SESSION" -> normalize(item.getSessionId());
			case "STATION" -> runtimeProfile == null ? normalize(item.getSessionId()) : normalize(runtimeProfile.stationId());
			default -> normalize(item.getSessionId());
		};
		String raw = String.join(
				"|",
				"tts-v1",
				normalize(reuseScope),
				scopePartition,
				normalize(provider.providerKey()),
				normalize(provider.baseUrl()),
				normalize(provider.adapter()),
				normalize(provider.defaultModelProfileId()),
				sha256(directive.normalizedText()),
				sha256(directive.voiceHint()),
				profileFingerprint(runtimeProfile),
				previousFailure == null ? "" : previousFailure.errorCode());
		return sha256(raw);
	}

	private String profileFingerprint(TtsRuntimeProfile profile) {
		if (profile == null) {
			return "";
		}
		return sha256(String.join(
				"|",
				normalize(profile.voiceProfileId()),
				normalize(profile.stationId()),
				normalize(profile.providerKey()),
				normalize(profile.engineType()),
				normalize(profile.speakerKey()),
				normalize(profile.styleKey()),
				profile.speed() == null ? "" : profile.speed().stripTrailingZeros().toPlainString(),
				canonicalValue(profile.providerOptions()),
				sha256(profile.referenceVoiceRef()),
				sha256(profile.consentPolicyRef())));
	}

	private String canonicalValue(Object value) {
		if (value == null) {
			return "";
		}
		if (value instanceof Map<?, ?> map) {
			List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
			entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
			return entries.stream()
					.map(entry -> String.valueOf(entry.getKey()) + "=" + canonicalValue(entry.getValue()))
					.reduce((left, right) -> left + "," + right)
					.orElse("");
		}
		if (value instanceof Iterable<?> iterable) {
			List<String> values = new ArrayList<>();
			iterable.forEach(entry -> values.add(canonicalValue(entry)));
			return String.join(",", values);
		}
		return String.valueOf(value).trim();
	}

	private List<ProviderRegistry.ResolvedProvider> resolveProviders(TtsRuntimeProfile runtimeProfile) {
		String preferredProviderKey = runtimeProfile == null ? null : runtimeProfile.providerKey();
		List<ProviderRegistry.ResolvedProvider> providers = providerRegistry.resolveChain(ProviderType.TTS, preferredProviderKey);
		if (preferredProviderKey != null && !preferredProviderKey.isBlank()
				&& providers.stream().noneMatch(provider -> preferredProviderKey.equals(provider.providerKey()))) {
			throw new TtsSynthesisException(ProviderErrorCode.PROVIDER_REJECTED, "TTS 音声プロファイルが指定する Provider は利用できません。");
		}
		return providers.isEmpty() ? List.of(placeholderProvider()) : providers;
	}

	private boolean isCompatible(TtsRuntimeProfile runtimeProfile, ProviderRegistry.ResolvedProvider provider) {
		if (runtimeProfile == null || runtimeProfile.engineType() == null) {
			return true;
		}
		return switch (runtimeProfile.engineType().toUpperCase(Locale.ROOT)) {
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
				|| provider.providerKey().toLowerCase(Locale.ROOT).contains("voicevox");
	}

	private ProviderRegistry.ResolvedProvider placeholderProvider() {
		return new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS, "tts", "seedshift-placeholder", "http://127.0.0.1", "/health", 5_000,
				List.of("TTS_GEN"), true);
	}

	private ProviderJobEntity createProviderJob(QueueItemEntity item, ProviderRegistry.ResolvedProvider provider) {
		return providerJobService.createQueuedJob(
				ProviderJobType.TTS_GEN, ProviderType.TTS, provider.providerKey(), item.getId(), item.getCorrelationId());
	}

	private SpeechDirectiveResponse toSpeechDirective(QueueItemEntity item, ScriptDirectiveSnapshot snapshot) {
		return new SpeechDirectiveResponse(
				item.getSpeechDirectiveId() == null ? "sd-" + item.getId() : item.getSpeechDirectiveId(),
				snapshot.text(), snapshot.normalizedText(), snapshot.pronunciationHints(), snapshot.emotion(), snapshot.tempo(),
				snapshot.pauseHints(), snapshot.personaRef(), snapshot.voiceHint(), item.getCorrelationId());
	}

	private void applyAsset(QueueItemEntity item, GeneratedAssetEntity asset, String contentOrigin) {
		item.setAssetId(asset.getId());
		item.setAssetUrl("/api/assets/audio/" + asset.getId() + ".wav");
		item.setContentOrigin(contentOrigin);
	}

	private boolean placeholderEnabled() {
		return Boolean.TRUE.equals(settingsStore.load().features().streaming().placeholderEnabled());
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(bytes.length * 2);
			for (byte current : bytes) {
				builder.append(String.format("%02x", current));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 が利用できません。", exception);
		}
	}
}
