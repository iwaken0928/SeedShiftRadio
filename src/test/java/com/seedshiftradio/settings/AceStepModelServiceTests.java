package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.seedshiftradio.common.api.ApiException;

class AceStepModelServiceTests {

	@TempDir
	Path tempDir;

	MusicGenWorkerGateway gateway;
	AceStepModelService service;
	SettingsDocument.MusicGenerationModelProfile fastProfile;

	@BeforeEach
	void setUp() {
		RadioSettingsStore store = new RadioSettingsStore(
				new ObjectMapper().findAndRegisterModules(),
				new RadioConfigProperties(tempDir.resolve("config.json").toString()));
		store.load();
		gateway = mock(MusicGenWorkerGateway.class);
		service = new AceStepModelService(store, gateway);
		fastProfile = SettingsDocument.MusicGenerationModelProfile.defaultAceStepProfiles().get("ace-ja-fast");
	}

	@Test
	void loadsSavedProfileIntoRequestedAceStepSlot() {
		MusicGenWorkerGateway.ResolvedMusicProvider provider = new MusicGenWorkerGateway.ResolvedMusicProvider(
				"ace-step",
				"http://127.0.0.1:8001",
				10_000,
				List.of("MUSIC_GEN", "ACE_STEP"),
				"ACE_STEP",
				null,
				"ace-ja-fast",
				SettingsDocument.MusicGenerationModelProfile.defaultAceStepProfiles());
		when(gateway.resolveProvider("ace-step")).thenReturn(provider);
		when(gateway.listModels(provider))
				.thenReturn(new MusicGenWorkerGateway.ModelCatalog(
						List.of(new MusicGenWorkerGateway.ModelInfo("acestep/acestep-v15-turbo", true, true)),
						"acestep-v15-turbo"));
		when(gateway.initializeModel(provider, fastProfile, 2))
				.thenReturn(new MusicGenWorkerGateway.ModelInitializationResult(
						"Model initialization completed",
						2,
						"acestep-v15-turbo",
						"acestep-5Hz-lm-0.6B",
						List.of("acestep-v15-turbo"),
						List.of("acestep-5Hz-lm-0.6B"),
						true));

		SettingsDtos.AceStepModelLoadResponse response = service.loadProfile(
				"ace-step",
				new SettingsDtos.AceStepModelLoadRequest("ace-ja-fast", 2));

		assertEquals("ace-step", response.providerKey());
		assertEquals("ace-ja-fast", response.profileId());
		assertEquals("acestep-v15-turbo", response.loadedModel());
		verify(gateway).initializeModel(provider, fastProfile, 2);
	}

	@Test
	void rejectsUnknownProfileBeforeCallingAceStep() {
		ApiException exception = assertThrows(
				ApiException.class,
				() -> service.loadProfile(
						"ace-step",
						new SettingsDtos.AceStepModelLoadRequest("unknown-profile", 1)));

		assertEquals("VALIDATION_ERROR", exception.getCode());
	}

	@Test
	void rejectsProfileWhoseModelIsNotAdvertisedByAceStep() {
		MusicGenWorkerGateway.ResolvedMusicProvider provider = new MusicGenWorkerGateway.ResolvedMusicProvider(
				"ace-step",
				"http://127.0.0.1:8001",
				10_000,
				List.of("MUSIC_GEN", "ACE_STEP"),
				"ACE_STEP",
				null,
				"ace-ja-fast",
				SettingsDocument.MusicGenerationModelProfile.defaultAceStepProfiles());
		when(gateway.resolveProvider("ace-step")).thenReturn(provider);
		when(gateway.listModels(provider))
				.thenReturn(new MusicGenWorkerGateway.ModelCatalog(
						List.of(new MusicGenWorkerGateway.ModelInfo("acestep-v15-sft", true, true)),
						"acestep-v15-sft"));

		ApiException exception = assertThrows(
				ApiException.class,
				() -> service.loadProfile(
						"ace-step",
						new SettingsDtos.AceStepModelLoadRequest("ace-ja-fast", 1)));

		assertEquals("VALIDATION_ERROR", exception.getCode());
	}

	@Test
	void rejectsSlotOutsideAceStepRange() {
		ApiException exception = assertThrows(
				ApiException.class,
				() -> service.loadProfile(
						"ace-step",
						new SettingsDtos.AceStepModelLoadRequest("ace-ja-fast", 4)));

		assertEquals("VALIDATION_ERROR", exception.getCode());
	}
}
