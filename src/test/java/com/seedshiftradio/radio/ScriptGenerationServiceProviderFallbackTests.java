package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.settings.GeneratedAssetService;
import com.seedshiftradio.settings.GeneratedAssetEntity;
import com.seedshiftradio.settings.ProviderJobEntity;
import com.seedshiftradio.settings.ProviderJobService;
import com.seedshiftradio.settings.ProviderRegistry;
import com.seedshiftradio.settings.RadioSettingsStore;
import com.seedshiftradio.settings.SettingsDocument;

@ExtendWith(MockitoExtension.class)
class ScriptGenerationServiceProviderFallbackTests {

	@Mock
	ContextAssembler contextAssembler;
	@Mock
	HttpScriptProvider httpScriptProvider;
	@Mock
	TemplateScriptProvider templateScriptProvider;
	@Mock
	JapaneseScriptNormalizer normalizer;
	@Mock
	SentenceSplitter sentenceSplitter;
	@Mock
	PronunciationDictionaryService pronunciationDictionaryService;
	@Mock
	PersonaStyleResolver personaStyleResolver;
	@Mock
	JapaneseQualityGuard qualityGuard;
	@Mock
	ClientCapabilitiesService clientCapabilitiesService;
	@Mock
	PlayoutSessionRepository playoutSessionRepository;
	@Mock
	GeneratedAssetService generatedAssetService;
	@Mock
	ProviderRegistry providerRegistry;
	@Mock
	ProviderJobService providerJobService;
	@Mock
	RadioSettingsStore settingsStore;

	ScriptGenerationService service;
	QueueItemEntity item;
	ScriptGenerationContext context;

	@BeforeEach
	void setUp() {
		service = new ScriptGenerationService(
				contextAssembler,
				httpScriptProvider,
				templateScriptProvider,
				normalizer,
				sentenceSplitter,
				pronunciationDictionaryService,
				personaStyleResolver,
				qualityGuard,
				clientCapabilitiesService,
				playoutSessionRepository,
				generatedAssetService,
				providerRegistry,
				providerJobService,
				settingsStore);
		SettingsDocument settingsDocument = org.mockito.Mockito.mock(SettingsDocument.class);
		when(settingsStore.load()).thenReturn(settingsDocument);
		when(settingsDocument.cache()).thenReturn(new SettingsDocument.CacheSettings(
				1L, 1L, 1L, 1, 1, 1, "STATION", "STATION", "GLOBAL", 1));

		item = new QueueItemEntity();
		item.setId("queue-script-1");
		item.setSessionId("session-script-1");
		item.setCorrelationId("corr-script-1");
		item.setSegmentType(SegmentType.TALK);
		item.setSlotRole(SlotRole.TOPIC);
		item.setTitle("夜の話題");

		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setId("session-script-1");
		session.setStationId("station-night");
		session.setCorrelationId("corr-script-1");
		context = new ScriptGenerationContext(session, item, null, null, null, null, "safe prompt");

		when(generatedAssetService.findLatestScriptAssetForQueueItem("queue-script-1")).thenReturn(Optional.empty());
		when(playoutSessionRepository.findById("session-script-1")).thenReturn(Optional.of(session));
		when(contextAssembler.assemble(session, item)).thenReturn(context);
		org.mockito.Mockito.lenient().when(normalizer.normalize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
		org.mockito.Mockito.lenient().when(sentenceSplitter.splitLongSentences(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
		org.mockito.Mockito.lenient().when(qualityGuard.inspect(anyString(), eq(context))).thenAnswer(invocation ->
				new JapaneseQualityGuard.QualityResult(invocation.getArgument(0), List.of()));
		org.mockito.Mockito.lenient().when(pronunciationDictionaryService.resolveHints(anyString())).thenReturn(List.of());
		org.mockito.Mockito.lenient().when(pronunciationDictionaryService.applyReadings(anyString(), eq(List.of())))
				.thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void recoverableFailureTriesNextExternalProvider() {
		ProviderRegistry.ResolvedProvider primary = provider("llm-primary", false);
		ProviderRegistry.ResolvedProvider fallback = provider("llm-fallback", true);
		ProviderJobEntity primaryJob = job("job-primary");
		ProviderJobEntity fallbackJob = job("job-fallback");
		when(providerRegistry.resolveChain(ProviderType.LLM)).thenReturn(List.of(primary, fallback));
		when(providerJobService.createQueuedJob(
				eq(ProviderJobType.SCRIPT_GEN), eq(ProviderType.LLM), anyString(), eq("queue-script-1"), eq("corr-script-1")))
				.thenReturn(primaryJob, fallbackJob);
		when(httpScriptProvider.generate(primary, context))
				.thenThrow(new ScriptGenerationException(ProviderErrorCode.PROVIDER_TIMEOUT, "timeout"));
		when(httpScriptProvider.generate(fallback, context))
				.thenReturn(new GeneratedScript("fallback script", List.of("LLM_GENERATED")));

		ScriptDirectiveSnapshot result = service.ensureScriptAsset(item);

		assertEquals("fallback script", result.normalizedText());
		verify(providerJobService).markFailed("job-primary", ProviderErrorCode.PROVIDER_TIMEOUT);
		verify(providerJobService).markSucceeded("job-fallback");
		verify(templateScriptProvider, never()).generate(any(), any());
		verify(generatedAssetService).createScriptAsset(
				eq("fallback script"),
				eq("llm-fallback:OLLAMA:qwen3:8b"),
				eq("queue-script-1"),
				eq("job-fallback"),
				any(Map.class));
	}

	@Test
	void nonRecoverableFailureStopsExternalChainBeforeTemplateFallback() {
		ProviderRegistry.ResolvedProvider primary = provider("llm-primary", false);
		ProviderRegistry.ResolvedProvider fallback = provider("llm-fallback", true);
		ProviderJobEntity primaryJob = job("job-primary");
		ProviderJobEntity templateJob = job("job-template");
		when(providerRegistry.resolveChain(ProviderType.LLM)).thenReturn(List.of(primary, fallback));
		when(providerJobService.createQueuedJob(
				eq(ProviderJobType.SCRIPT_GEN), eq(ProviderType.LLM), anyString(), eq("queue-script-1"), eq("corr-script-1")))
				.thenReturn(primaryJob, templateJob);
		when(httpScriptProvider.generate(primary, context))
				.thenThrow(new ScriptGenerationException(ProviderErrorCode.PROVIDER_AUTH_FAILED, "auth failed"));
		when(templateScriptProvider.generate(null, context))
				.thenReturn(new GeneratedScript("template script", List.of("DETERMINISTIC_FALLBACK")));

		ScriptDirectiveSnapshot result = service.ensureScriptAsset(item);

		assertEquals("template script", result.normalizedText());
		verify(providerJobService).markFailed("job-primary", ProviderErrorCode.PROVIDER_AUTH_FAILED);
		verify(httpScriptProvider, never()).generate(fallback, context);
		verify(templateScriptProvider).generate(null, context);
		verify(providerJobService).markSucceeded("job-template");
	}

	@Test
	void reusableScriptAssetSkipsProviderAndKeepsCurrentJobTrace() {
		ProviderRegistry.ResolvedProvider provider = provider("llm-primary", false);
		when(providerRegistry.resolveChain(ProviderType.LLM)).thenReturn(List.of(provider));
		GeneratedAssetEntity reusable = mock(GeneratedAssetEntity.class);
		when(reusable.getId()).thenReturn("script-cache-source");
		when(reusable.getMetadata()).thenReturn(Map.of(
				"text", "cached script",
				"normalizedText", "cached script",
				"pronunciationHints", List.of(),
				"pauseHints", List.of(),
				"emotion", "calm",
				"tempo", "medium",
				"safetyFlags", List.of()));
		when(generatedAssetService.findReusableAsset(eq(GeneratedAssetType.SCRIPT), anyString()))
				.thenReturn(Optional.of(reusable));
		ProviderJobEntity cacheJob = job("job-cache-hit");
		when(providerJobService.createQueuedJob(
				eq(ProviderJobType.SCRIPT_GEN), eq(ProviderType.LLM), eq("llm-primary"),
				eq("queue-script-1"), eq("corr-script-1"))).thenReturn(cacheJob);
		when(generatedAssetService.cloneAssetForQueue(
				eq(reusable), eq("queue-script-1"), eq("job-cache-hit"), anyString(), any(Map.class)))
				.thenAnswer(invocation -> {
					GeneratedAssetEntity cloned = mock(GeneratedAssetEntity.class);
					when(cloned.getMetadata()).thenReturn(invocation.getArgument(4));
					return cloned;
				});

		ScriptDirectiveSnapshot result = service.ensureScriptAsset(item);

		assertEquals("cached script", result.normalizedText());
		verify(providerJobService).markRunning("job-cache-hit", "llm-primary", "cache-hit:script-cache-source");
		verify(providerJobService).markSucceeded("job-cache-hit");
		verify(httpScriptProvider, never()).generate(any(), any());
		verify(generatedAssetService, never()).createScriptAsset(anyString(), anyString(), anyString(), anyString(), any(Map.class));
	}

	private ProviderRegistry.ResolvedProvider provider(String providerKey, boolean fallback) {
		return new ProviderRegistry.ResolvedProvider(
				ProviderType.LLM,
				"llm",
				providerKey,
				"http://127.0.0.1:11434",
				"/api/tags",
				1_000,
				List.of("SCRIPT_GEN"),
				HttpScriptProvider.ADAPTER_OLLAMA,
				null,
				"qwen3:8b",
				Map.of(),
				fallback);
	}

	private ProviderJobEntity job(String id) {
		ProviderJobEntity job = mock(ProviderJobEntity.class);
		when(job.getId()).thenReturn(id);
		return job;
	}
}
