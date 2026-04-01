package com.seedshiftradio.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.seedshiftradio.radio.QueueItemEntity;

@Component
public class PlaceholderTtsProvider implements TtsProvider {

	private final PlaceholderAudioFactory placeholderAudioFactory;

	public PlaceholderTtsProvider(PlaceholderAudioFactory placeholderAudioFactory) {
		this.placeholderAudioFactory = placeholderAudioFactory;
	}

	@Override
	public SynthesizedAudio synthesize(ProviderRegistry.ResolvedProvider provider, QueueItemEntity item) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("queueItemId", item.getId());
		metadata.put("segmentType", item.getSegmentType().name());
		metadata.put("slotRole", item.getSlotRole().name());
		metadata.put("placeholder", true);
		metadata.put("providerKey", provider.providerKey());
		return new SynthesizedAudio(
				placeholderAudioFactory.createSilentWav(item.getDurationMs()),
				provider.providerKey() + ":placeholder",
				metadata);
	}
}
