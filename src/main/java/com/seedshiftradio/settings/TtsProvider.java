package com.seedshiftradio.settings;

import java.util.Map;

import com.seedshiftradio.radio.QueueItemEntity;

public interface TtsProvider {

	SynthesizedAudio synthesize(ProviderRegistry.ResolvedProvider provider, QueueItemEntity item);

	record SynthesizedAudio(byte[] audioBytes, String providerFingerprint, Map<String, Object> metadata) {
	}
}
