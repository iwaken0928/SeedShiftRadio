package com.seedshiftradio.radio;

import java.util.List;

public record SpeechDirectiveResponse(
		String id,
		String text,
		String normalizedText,
		List<PronunciationHint> pronunciationHints,
		String emotion,
		String tempo,
		List<PauseHint> pauseHints,
		String personaRef,
		String voiceHint,
		String correlationId) {
}
