package com.seedshiftradio.radio;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class PronunciationDictionaryService {

	private static final List<PronunciationHint> COMMON_HINTS = List.of(
			new PronunciationHint("AI", "エーアイ"),
			new PronunciationHint("SeedShiftRadio", "シードシフトレディオ"),
			new PronunciationHint("VOICEVOX", "ボイスボックス"),
			new PronunciationHint("MusicGen", "ミュージックジェン"));

	public List<PronunciationHint> resolveHints(String normalizedText) {
		if (normalizedText == null || normalizedText.isBlank()) {
			return List.of();
		}
		return COMMON_HINTS.stream()
				.filter(hint -> normalizedText.contains(hint.surface()))
				.toList();
	}
}
