package com.seedshiftradio.radio;

import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class PersonaStyleResolver {

	private static final String IRODORI_TTS = "IRODORI_TTS";
	private static final Map<String, String> ALLOWED_STYLE_PRESETS = Map.ofEntries(
			Map.entry("soft", "😌"),
			Map.entry("gentle", "😌"),
			Map.entry("calm", "😌"),
			Map.entry("bright", "😄"),
			Map.entry("cheerful", "😄"),
			Map.entry("happy", "😄"),
			Map.entry("lively", "😄"),
			Map.entry("energetic", "😄"),
			Map.entry("fast", "⏩"),
			Map.entry("slow", "🐢"),
			Map.entry("narration", "🎙️"));

	public String applyStyle(
			String normalizedText,
			String engineType,
			String emotion,
			String tempo,
			String styleKey) {
		String text = normalizedText == null ? "" : normalizedText;
		if (text.isBlank() || !IRODORI_TTS.equalsIgnoreCase(normalize(engineType))) {
			return text;
		}
		String preset = resolvePreset(emotion, tempo, styleKey);
		if (preset == null || text.startsWith(preset)) {
			return text;
		}
		return preset + text;
	}

	String resolvePreset(String emotion, String tempo, String styleKey) {
		String requestedStyle = resolveStyleKey(styleKey);
		if (styleKey != null && !styleKey.isBlank() && requestedStyle == null) {
			return null;
		}
		if (requestedStyle != null) {
			return ALLOWED_STYLE_PRESETS.get(requestedStyle);
		}
		String emotionPreset = ALLOWED_STYLE_PRESETS.get(normalize(emotion));
		return emotionPreset != null ? emotionPreset : ALLOWED_STYLE_PRESETS.get(normalize(tempo));
	}

	String resolveStyleKey(String styleKey) {
		String normalized = normalize(styleKey);
		return normalized.isBlank() || !ALLOWED_STYLE_PRESETS.containsKey(normalized) ? null : normalized;
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
