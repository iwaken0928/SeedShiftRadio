package com.seedshiftradio.radio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record ScriptDirectiveSnapshot(
		String text,
		String normalizedText,
		List<PronunciationHint> pronunciationHints,
		String emotion,
		String tempo,
		List<PauseHint> pauseHints,
		String personaRef,
		String voiceHint,
		List<String> safetyFlags) {

	public ScriptDirectiveSnapshot {
		pronunciationHints = pronunciationHints == null ? List.of() : List.copyOf(pronunciationHints);
		pauseHints = pauseHints == null ? List.of() : List.copyOf(pauseHints);
		safetyFlags = safetyFlags == null ? List.of() : List.copyOf(safetyFlags);
	}

	public static ScriptDirectiveSnapshot fromMetadata(Map<String, Object> metadata) {
		return new ScriptDirectiveSnapshot(
				stringValue(metadata.get("text")),
				stringValue(metadata.get("normalizedText")),
				pronunciationHints(metadata.get("pronunciationHints")),
				stringValue(metadata.get("emotion")),
				stringValue(metadata.get("tempo")),
				pauseHints(metadata.get("pauseHints")),
				stringValue(metadata.get("personaRef")),
				stringValue(metadata.get("voiceHint")),
				stringList(metadata.get("safetyFlags")));
	}

	private static List<PronunciationHint> pronunciationHints(Object value) {
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		List<PronunciationHint> hints = new ArrayList<>();
		for (Object element : list) {
			if (element instanceof Map<?, ?> map) {
				String surface = stringValue(map.get("surface"));
				String reading = stringValue(map.get("reading"));
				if (surface != null && reading != null) {
					hints.add(new PronunciationHint(surface, reading));
				}
			}
		}
		return List.copyOf(hints);
	}

	private static List<PauseHint> pauseHints(Object value) {
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		List<PauseHint> hints = new ArrayList<>();
		for (Object element : list) {
			if (element instanceof Map<?, ?> map) {
				Integer index = intValue(map.get("index"));
				Integer durationMs = intValue(map.get("durationMs"));
				if (index != null && durationMs != null) {
					hints.add(new PauseHint(index, durationMs));
				}
			}
		}
		return List.copyOf(hints);
	}

	private static List<String> stringList(Object value) {
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		return list.stream()
				.map(ScriptDirectiveSnapshot::stringValue)
				.filter(item -> item != null && !item.isBlank())
				.toList();
	}

	private static String stringValue(Object value) {
		return value instanceof String string && !string.isBlank() ? string : null;
	}

	private static Integer intValue(Object value) {
		if (value instanceof Integer integer) {
			return integer;
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value instanceof String string) {
			try {
				return Integer.parseInt(string);
			} catch (NumberFormatException exception) {
				return null;
			}
		}
		return null;
	}
}
