package com.seedshiftradio.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seedshiftradio.settings.RadioConfigProperties;
import com.seedshiftradio.settings.RadioSettingsStore;
import com.seedshiftradio.settings.SettingsDocument;

public final class TestSettingsFixture {

	private TestSettingsFixture() {
	}

	public static void writeFastLocalConfig(String rawPath) {
		SettingsDocument defaults = SettingsDocument.defaults().normalize();
		SettingsDocument document = new SettingsDocument(
				defaults.version(),
				defaults.schemaVersion(),
				Instant.parse("2026-03-20T09:00:00Z"),
				defaults.server(),
				defaults.paths(),
				defaults.playout(),
				defaults.cache(),
				defaults.programming(),
				new SettingsDocument.ProviderCatalog(
						quickDownProviderGroup("ollama", "SCRIPT_GEN"),
						quickDownProviderGroup("voicevox", "TTS_GEN"),
						quickDownProviderGroup("ace-step", "MUSIC_GEN")),
				defaults.security(),
				defaults.features())
				.normalize();
		RadioSettingsStore store = new RadioSettingsStore(
				new ObjectMapper().findAndRegisterModules(),
				new RadioConfigProperties(rawPath));
		store.save(document);
	}

	private static SettingsDocument.ProviderGroup quickDownProviderGroup(String providerKey, String capability) {
		return new SettingsDocument.ProviderGroup(
				providerKey,
				List.of(),
				Map.of(
						providerKey,
						new SettingsDocument.ProviderEndpoint(
								"http://127.0.0.1:1",
								"/health",
								100,
								List.of(capability))))
				.normalize();
	}
}
