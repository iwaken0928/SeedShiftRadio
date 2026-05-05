package com.seedshiftradio.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.radio.SpeechDirectiveResponse;

@Component
public class PlaceholderTtsProvider implements TtsProvider {

	private final PlaceholderAudioFactory placeholderAudioFactory;

	public PlaceholderTtsProvider(PlaceholderAudioFactory placeholderAudioFactory) {
		this.placeholderAudioFactory = placeholderAudioFactory;
	}

	@Override
	public SynthesizedAudio synthesize(ProviderRegistry.ResolvedProvider provider, QueueItemEntity item, SpeechDirectiveResponse directive) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("queueItemId", item.getId());
		metadata.put("segmentType", item.getSegmentType().name());
		metadata.put("slotRole", item.getSlotRole().name());
		metadata.put("placeholder", true);
		metadata.put("providerKey", provider.providerKey());
		metadata.put("speechDirectiveId", directive.id());
		metadata.put("textHash", sha256(directive.normalizedText()));
		metadata.put("normalizedText", directive.normalizedText());
		metadata.put("pronunciationHints", directive.pronunciationHints().stream()
				.map(hint -> Map.of("surface", hint.surface(), "reading", hint.reading()))
				.toList());
		metadata.put("pauseHints", directive.pauseHints().stream()
				.map(hint -> Map.of("index", hint.index(), "durationMs", hint.durationMs()))
				.toList());
		metadata.put("voiceHint", directive.voiceHint());
		metadata.put("personaRef", directive.personaRef());
		metadata.put("archiveEligible", false);
		return new SynthesizedAudio(
				placeholderAudioFactory.createSilentWav(item.getDurationMs()),
				provider.providerKey() + ":placeholder",
				metadata);
	}

	private String sha256(String value) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(bytes.length * 2);
			for (byte current : bytes) {
				builder.append(String.format("%02x", current));
			}
			return builder.toString();
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 が利用できません。", exception);
		}
	}
}
