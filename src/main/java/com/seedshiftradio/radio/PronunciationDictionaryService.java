package com.seedshiftradio.radio;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
				.filter(hint -> surfacePattern(hint.surface()).matcher(normalizedText).find())
				.toList();
	}

	public String applyReadings(String normalizedText, List<PronunciationHint> hints) {
		if (normalizedText == null || normalizedText.isBlank() || hints == null || hints.isEmpty()) {
			return normalizedText == null ? "" : normalizedText;
		}
		String corrected = normalizedText;
		List<PronunciationHint> orderedHints = hints.stream()
				.filter(hint -> hint != null
						&& hint.surface() != null && !hint.surface().isBlank()
						&& hint.reading() != null && !hint.reading().isBlank())
				.sorted(Comparator.comparingInt((PronunciationHint hint) -> hint.surface().length()).reversed())
				.toList();
		for (PronunciationHint hint : orderedHints) {
			Pattern surfacePattern = surfacePattern(hint.surface());
			corrected = surfacePattern.matcher(corrected).replaceAll(Matcher.quoteReplacement(hint.reading()));
		}
		return corrected;
	}

	private Pattern surfacePattern(String surface) {
		String quoted = Pattern.quote(surface);
		if (surface.matches("[A-Za-z0-9]+")) {
			return Pattern.compile("(?<![A-Za-z0-9])" + quoted + "(?![A-Za-z0-9])");
		}
		return Pattern.compile(quoted);
	}
}
