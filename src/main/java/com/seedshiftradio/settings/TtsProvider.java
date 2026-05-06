package com.seedshiftradio.settings;

import java.util.Map;

import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.radio.SpeechDirectiveResponse;

public interface TtsProvider {

	SynthesizedAudio synthesize(ProviderRegistry.ResolvedProvider provider, QueueItemEntity item, SpeechDirectiveResponse directive);

	record SynthesizedAudio(byte[] audioBytes, String providerFingerprint, Map<String, Object> metadata) {
	}
}
