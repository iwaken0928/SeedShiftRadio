package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.seedshiftradio.domain.ProviderType;

class ProviderRegistryTests {

	@Test
	void preferredVoiceProfileProviderComesBeforeConfiguredDefaultAndFallback() {
		RadioSettingsStore store = mock(RadioSettingsStore.class);
		SettingsDocument document = mock(SettingsDocument.class);
		SettingsDocument.ProviderEndpoint endpoint = new SettingsDocument.ProviderEndpoint(
				"http://127.0.0.1:8088", "/health", 1_000, List.of("TTS_GEN"));
		SettingsDocument.ProviderGroup tts = new SettingsDocument.ProviderGroup(
				"voicevox",
				List.of("tts-backup"),
				Map.of("voicevox", endpoint, "irodori", endpoint, "tts-backup", endpoint));
		when(store.load()).thenReturn(document);
		when(document.providers()).thenReturn(new SettingsDocument.ProviderCatalog(null, tts, null));

		List<ProviderRegistry.ResolvedProvider> providers = new ProviderRegistry(store)
				.resolveChain(ProviderType.TTS, "irodori");

		assertEquals(List.of("irodori", "voicevox", "tts-backup"), providers.stream().map(ProviderRegistry.ResolvedProvider::providerKey).toList());
		assertEquals(List.of(false, true, true), providers.stream().map(ProviderRegistry.ResolvedProvider::fallback).toList());
	}
}
