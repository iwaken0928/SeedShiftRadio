package com.seedshiftradio.settings;

import java.util.Map;

import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.radio.SpeechDirectiveResponse;

public interface TtsProvider {

	default SynthesizedAudio synthesize(ProviderRegistry.ResolvedProvider provider, QueueItemEntity item, SpeechDirectiveResponse directive) {
		return synthesize(provider, item, directive, null);
	}

	SynthesizedAudio synthesize(
			ProviderRegistry.ResolvedProvider provider,
			QueueItemEntity item,
			SpeechDirectiveResponse directive,
			TtsRuntimeProfile runtimeProfile);

	record SynthesizedAudio(byte[] audioBytes, String providerFingerprint, Map<String, Object> metadata) {
	}
}
