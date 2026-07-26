package com.seedshiftradio.radio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.settings.GeneratedAssetEntity;
import com.seedshiftradio.settings.GeneratedAssetService;
import com.seedshiftradio.settings.ProviderErrorClassifier;
import com.seedshiftradio.settings.ProviderJobEntity;
import com.seedshiftradio.settings.ProviderJobService;
import com.seedshiftradio.settings.ProviderRegistry;
import com.seedshiftradio.settings.RadioSettingsStore;
import com.seedshiftradio.settings.ProviderRuntimeException;

@Service
public class ScriptGenerationService {
	private static final List<String> NO_REUSE_SCOPES = List.of("DISABLED", "ARCHIVE_ONLY");

	private final ContextAssembler contextAssembler;
	private final HttpScriptProvider httpScriptProvider;
	private final TemplateScriptProvider templateScriptProvider;
	private final JapaneseScriptNormalizer normalizer;
	private final SentenceSplitter sentenceSplitter;
	private final PronunciationDictionaryService pronunciationDictionaryService;
	private final PersonaStyleResolver personaStyleResolver;
	private final JapaneseQualityGuard qualityGuard;
	private final ClientCapabilitiesService clientCapabilitiesService;
	private final PlayoutSessionRepository playoutSessionRepository;
	private final GeneratedAssetService generatedAssetService;
	private final ProviderRegistry providerRegistry;
	private final ProviderJobService providerJobService;
	private final RadioSettingsStore settingsStore;

	public ScriptGenerationService(
			ContextAssembler contextAssembler,
			HttpScriptProvider httpScriptProvider,
			TemplateScriptProvider templateScriptProvider,
			JapaneseScriptNormalizer normalizer,
			SentenceSplitter sentenceSplitter,
			PronunciationDictionaryService pronunciationDictionaryService,
			PersonaStyleResolver personaStyleResolver,
			JapaneseQualityGuard qualityGuard,
			ClientCapabilitiesService clientCapabilitiesService,
			PlayoutSessionRepository playoutSessionRepository,
			GeneratedAssetService generatedAssetService,
			ProviderRegistry providerRegistry,
			ProviderJobService providerJobService,
			RadioSettingsStore settingsStore) {
		this.contextAssembler = contextAssembler;
		this.httpScriptProvider = httpScriptProvider;
		this.templateScriptProvider = templateScriptProvider;
		this.normalizer = normalizer;
		this.sentenceSplitter = sentenceSplitter;
		this.pronunciationDictionaryService = pronunciationDictionaryService;
		this.personaStyleResolver = personaStyleResolver;
		this.qualityGuard = qualityGuard;
		this.clientCapabilitiesService = clientCapabilitiesService;
		this.playoutSessionRepository = playoutSessionRepository;
		this.generatedAssetService = generatedAssetService;
		this.providerRegistry = providerRegistry;
		this.providerJobService = providerJobService;
		this.settingsStore = settingsStore;
	}

	@Transactional
	public ScriptDirectiveSnapshot ensureScriptAsset(QueueItemEntity item) {
		return findPersistedSnapshot(item)
				.orElseGet(() -> createScriptAsset(item));
	}

	@Transactional
	public SpeechDirectiveResponse resolveDirective(PlayoutSessionEntity session, QueueItemEntity item, String clientId) {
		ScriptGenerationContext context = contextAssembler.assemble(session, item);
		ScriptDirectiveSnapshot snapshot = findPersistedSnapshot(item)
				.orElseGet(() -> createScriptAsset(item));
		String voiceHint = resolveVoiceHint(context, clientId);
		return new SpeechDirectiveResponse(
				item.getSpeechDirectiveId() == null ? "sd-" + item.getId() : item.getSpeechDirectiveId(),
				snapshot.text(),
				snapshot.normalizedText(),
				snapshot.pronunciationHints(),
				snapshot.emotion(),
				snapshot.tempo(),
				snapshot.pauseHints(),
				snapshot.personaRef(),
				voiceHint == null ? snapshot.voiceHint() : voiceHint,
				session.getCorrelationId());
	}

	private Optional<ScriptDirectiveSnapshot> findPersistedSnapshot(QueueItemEntity item) {
		if (item.getId() == null || item.getId().isBlank()) {
			return Optional.empty();
		}
		return generatedAssetService.findLatestScriptAssetForQueueItem(item.getId())
				.map(GeneratedAssetEntity::getMetadata)
				.map(ScriptDirectiveSnapshot::fromMetadata);
	}

	private ScriptDirectiveSnapshot createScriptAsset(QueueItemEntity item) {
		PlayoutSessionEntity session = playoutSessionRepository.findById(item.getSessionId())
				.orElseThrow(() -> new IllegalStateException("script generation target session is missing: " + item.getSessionId()));
		ScriptGenerationContext context = contextAssembler.assemble(session, item);
		for (ProviderRegistry.ResolvedProvider provider : providerRegistry.resolveChain(ProviderType.LLM)) {
			String cacheKey = buildCacheKey(context, provider);
			Optional<GeneratedAssetEntity> reusableAsset = findReusableScriptAsset(cacheKey);
			if (reusableAsset.isPresent()) {
				return reuseScriptAsset(item, provider.providerKey(), cacheKey, reusableAsset.orElseThrow());
			}
			ProviderJobEntity providerJob = createRunningJob(item, provider.providerKey());
			ScriptDirectiveSnapshot snapshot;
			try {
				snapshot = buildSnapshot(
						httpScriptProvider,
						provider,
						context,
						resolveVoiceHint(context, null));
			} catch (ProviderRuntimeException exception) {
				providerJobService.markFailed(providerJob.getId(), exception.providerErrorCode());
				if (!ProviderErrorClassifier.fallbackAllowed(ProviderType.LLM, exception.providerErrorCode())) {
					break;
				}
				continue;
			} catch (RuntimeException exception) {
				providerJobService.markFailed(providerJob.getId(), ProviderErrorCode.PROVIDER_INTERRUPTED);
				throw exception;
			}
			providerJobService.markSucceeded(providerJob.getId());
			persistScriptAsset(item, context, snapshot, provider, providerJob.getId(), cacheKey);
			return snapshot;
		}
		return createTemplateScriptAsset(item, context);
	}

	private ScriptDirectiveSnapshot createTemplateScriptAsset(QueueItemEntity item, ScriptGenerationContext context) {
		String providerKey = "template-script";
		String cacheKey = buildCacheKey(context, null);
		Optional<GeneratedAssetEntity> reusableAsset = findReusableScriptAsset(cacheKey);
		if (reusableAsset.isPresent()) {
			return reuseScriptAsset(item, providerKey, cacheKey, reusableAsset.orElseThrow());
		}
		ProviderJobEntity providerJob = createRunningJob(item, providerKey);
		ScriptDirectiveSnapshot snapshot;
		try {
			snapshot = buildSnapshot(
					templateScriptProvider,
					null,
					context,
					resolveVoiceHint(context, null));
		} catch (ProviderRuntimeException exception) {
			providerJobService.markFailed(providerJob.getId(), exception.providerErrorCode());
			throw exception;
		} catch (RuntimeException exception) {
			providerJobService.markFailed(providerJob.getId(), ProviderErrorCode.PROVIDER_INTERRUPTED);
			throw exception;
		}
		providerJobService.markSucceeded(providerJob.getId());
		generatedAssetService.createScriptAsset(
				snapshot.normalizedText(),
				"template-script:deterministic",
				item.getId(),
				providerJob.getId(),
				metadata(item, context, snapshot, providerKey, providerJob.getId(), cacheKey, false));
		return snapshot;
	}

	private ProviderJobEntity createRunningJob(QueueItemEntity item, String providerKey) {
		return createRunningJob(item, providerKey, "script-" + item.getId());
	}

	private ProviderJobEntity createRunningJob(QueueItemEntity item, String providerKey, String externalRef) {
		ProviderJobEntity providerJob = providerJobService.createQueuedJob(
				ProviderJobType.SCRIPT_GEN,
				ProviderType.LLM,
				providerKey,
				item.getId(),
				item.getCorrelationId());
		providerJobService.markRunning(providerJob.getId(), providerKey, externalRef);
		return providerJob;
	}

	private void persistScriptAsset(
			QueueItemEntity item,
			ScriptGenerationContext context,
			ScriptDirectiveSnapshot snapshot,
			ProviderRegistry.ResolvedProvider provider,
			String providerJobId,
			String cacheKey) {
		generatedAssetService.createScriptAsset(
				snapshot.normalizedText(),
				providerFingerprint(provider),
				item.getId(),
				providerJobId,
				metadata(item, context, snapshot, provider.providerKey(), providerJobId, cacheKey, false));
	}

	private Optional<GeneratedAssetEntity> findReusableScriptAsset(String cacheKey) {
		return cacheKey == null
				? Optional.empty()
				: generatedAssetService.findReusableAsset(GeneratedAssetType.SCRIPT, cacheKey);
	}

	private ScriptDirectiveSnapshot reuseScriptAsset(
			QueueItemEntity item,
			String providerKey,
			String cacheKey,
			GeneratedAssetEntity reusableAsset) {
		ProviderJobEntity providerJob = createRunningJob(item, providerKey, "cache-hit:" + reusableAsset.getId());
		Map<String, Object> metadata = new LinkedHashMap<>(reusableAsset.getMetadata());
		metadata.put("queueItemId", item.getId());
		metadata.put("providerKey", providerKey);
		metadata.put("providerJobId", providerJob.getId());
		metadata.put("cacheKey", cacheKey);
		metadata.put("cacheHit", true);
		metadata.put("sourceAssetId", reusableAsset.getId());
		try {
			GeneratedAssetEntity cloned = generatedAssetService.cloneAssetForQueue(
					reusableAsset, item.getId(), providerJob.getId(), cacheKey, metadata);
			providerJobService.markSucceeded(providerJob.getId());
			return ScriptDirectiveSnapshot.fromMetadata(cloned.getMetadata());
		} catch (RuntimeException exception) {
			providerJobService.markFailed(providerJob.getId(), ProviderErrorCode.PROVIDER_INTERRUPTED);
			throw exception;
		}
	}

	private String providerFingerprint(ProviderRegistry.ResolvedProvider provider) {
		return String.join(":",
				provider.providerKey(),
				provider.adapter(),
				provider.defaultModelProfileId());
	}

	private ScriptDirectiveSnapshot buildSnapshot(
			ScriptProvider selectedProvider,
			ProviderRegistry.ResolvedProvider provider,
			ScriptGenerationContext context,
			String voiceHint) {
		GeneratedScript script = selectedProvider.generate(provider, context);
		String normalized = normalizer.normalize(script.text());
		normalized = sentenceSplitter.splitLongSentences(normalized);
		JapaneseQualityGuard.QualityResult quality = qualityGuard.inspect(normalized, context);
		List<PronunciationHint> pronunciationHints = pronunciationDictionaryService.resolveHints(quality.text());
		String correctedText = pronunciationDictionaryService.applyReadings(quality.text(), pronunciationHints);
		String emotion = resolveEmotion(context);
		String tempo = resolveTempo(context);
		String finalText = context.voiceProfile() == null
				? correctedText
				: personaStyleResolver.applyStyle(
						correctedText,
						context.voiceProfile().getEngineType(),
						emotion,
						tempo,
						context.voiceProfile().getStyleKey());
		java.util.stream.Stream<String> contextFlags = context.letter() == null
				? java.util.stream.Stream.empty()
				: java.util.stream.Stream.of("LETTER_SUMMARIZED");
		List<String> safetyFlags = java.util.stream.Stream.concat(
				java.util.stream.Stream.concat(script.safetyFlags().stream(), quality.safetyFlags().stream()),
				contextFlags)
				.distinct()
				.toList();
		return new ScriptDirectiveSnapshot(
				script.text(),
				finalText,
				pronunciationHints,
				emotion,
				tempo,
				resolvePauseHints(context.item()),
				context.personality() != null
						? context.personality().getId()
						: context.station() != null ? context.station().getLanguagePersonaId() : null,
				voiceHint,
				safetyFlags);
	}

	private Map<String, Object> metadata(
			QueueItemEntity item,
			ScriptGenerationContext context,
			ScriptDirectiveSnapshot snapshot,
			String providerKey,
			String providerJobId,
			String cacheKey,
			boolean cacheHit) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("queueItemId", item.getId());
		metadata.put("providerKey", providerKey);
		metadata.put("providerJobId", providerJobId);
		metadata.put("cacheKey", cacheKey);
		metadata.put("cacheHit", cacheHit);
		metadata.put("segmentType", item.getSegmentType().name());
		metadata.put("slotRole", item.getSlotRole().name());
		metadata.put("text", snapshot.text());
		metadata.put("normalizedText", snapshot.normalizedText());
		metadata.put("textHash", sha256(snapshot.normalizedText()));
		metadata.put("promptHash", sha256(context.prompt()));
		metadata.put("pronunciationHints", snapshot.pronunciationHints().stream()
				.map(hint -> Map.of("surface", hint.surface(), "reading", hint.reading()))
				.toList());
		metadata.put("pauseHints", snapshot.pauseHints().stream()
				.map(hint -> Map.of("index", hint.index(), "durationMs", hint.durationMs()))
				.toList());
		metadata.put("emotion", snapshot.emotion());
		metadata.put("tempo", snapshot.tempo());
		metadata.put("personaRef", snapshot.personaRef());
		metadata.put("voiceHint", snapshot.voiceHint());
		metadata.put("safetyFlags", snapshot.safetyFlags());
		metadata.put("archiveEligible", archiveEligible(item, snapshot));
		if (item.getLetterId() != null && !item.getLetterId().isBlank()) {
			metadata.put("letterId", item.getLetterId());
		}
		return metadata;
	}

	private String buildCacheKey(ScriptGenerationContext context, ProviderRegistry.ResolvedProvider provider) {
		if (context.item().getLetterId() != null && !context.item().getLetterId().isBlank()) {
			return null;
		}
		String reuseScope = settingsStore.load().cache().scriptReuseScope();
		if (NO_REUSE_SCOPES.contains(reuseScope)) {
			return null;
		}
		String scopePartition = switch (reuseScope) {
			case "GLOBAL" -> "global";
			case "SESSION" -> normalize(context.session().getId());
			case "STATION" -> context.station() == null
					? normalize(context.session().getId())
					: normalize(context.station().getId());
			default -> normalize(context.session().getId());
		};
		String raw = String.join(
				"|",
				"script-v1",
				normalize(reuseScope),
				scopePartition,
				provider == null ? "template-script" : normalize(provider.providerKey()),
				provider == null ? "deterministic" : normalize(provider.baseUrl()),
				provider == null ? "template" : normalize(provider.adapter()),
				provider == null ? "" : normalize(provider.defaultModelProfileId()),
				normalize(context.item().getSegmentType().name()),
				normalize(context.item().getSlotRole().name()),
				normalize(context.item().getProgramBlockId()),
				normalize(context.item().getProgramSlotId()),
				context.personality() == null ? "" : normalize(context.personality().getId()),
				sha256(context.prompt()));
		return sha256(raw);
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
	}

	private boolean archiveEligible(QueueItemEntity item, ScriptDirectiveSnapshot snapshot) {
		return item.getSegmentType() == SegmentType.TALK
				&& (item.getLetterId() == null || item.getLetterId().isBlank())
				&& snapshot.safetyFlags().stream().noneMatch(flag -> flag.contains("LETTER"));
	}

	private String resolveEmotion(ScriptGenerationContext context) {
		String tone = context.personality() == null || context.personality().getLanguageTone() == null
				? "calm"
				: context.personality().getLanguageTone().toLowerCase(java.util.Locale.ROOT);
		if (tone.contains("bright") || tone.contains("cheer") || tone.contains("happy")) {
			return "bright";
		}
		if (tone.contains("energetic") || tone.contains("lively")) {
			return "lively";
		}
		return "calm";
	}

	private String resolveTempo(ScriptGenerationContext context) {
		if (context.item().getSegmentType() == SegmentType.MUSIC_AI || context.item().getSegmentType() == SegmentType.MUSIC_LOCAL) {
			return "slow";
		}
		String tone = context.personality() == null || context.personality().getLanguageTone() == null
				? ""
				: context.personality().getLanguageTone().toLowerCase(java.util.Locale.ROOT);
		if (tone.contains("energetic") || tone.contains("lively")) {
			return "fast";
		}
		return "medium";
	}

	private List<PauseHint> resolvePauseHints(QueueItemEntity item) {
		if (item.getSlotRole() == com.seedshiftradio.domain.SlotRole.OPENING
				|| item.getSlotRole() == com.seedshiftradio.domain.SlotRole.ENDING) {
			return List.of(new PauseHint(8, 200));
		}
		return List.of();
	}

	private String resolveVoiceHint(ScriptGenerationContext context, String clientId) {
		ClientCapabilitiesRecord capabilities = clientId == null || clientId.isBlank()
				? null
				: clientCapabilitiesService.latest(clientId);
		if (capabilities != null
				&& capabilities.supportsClientSideTts()
				&& capabilities.preferredPlaybackMode() == com.seedshiftradio.domain.PlaybackMode.CLIENT_TTS
				&& capabilities.localVoiceProfiles() != null
				&& !capabilities.localVoiceProfiles().isEmpty()) {
			ClientCapabilitiesRequest.LocalVoiceProfile localVoiceProfile = capabilities.localVoiceProfiles().getFirst();
			return localVoiceProfile.engine() + ":" + localVoiceProfile.profileKey();
		}
		if (context.voiceProfile() == null) {
			return null;
		}
		String base = context.voiceProfile().getEngineType() + ":" + context.voiceProfile().getSpeakerKey();
		String styleKey = context.voiceProfile().getStyleKey();
		if ("IRODORI_TTS".equalsIgnoreCase(context.voiceProfile().getEngineType())) {
			styleKey = personaStyleResolver.resolveStyleKey(styleKey);
		}
		return styleKey == null || styleKey.isBlank()
				? base
				: base + ":" + styleKey;
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
