package com.seedshiftradio.radio;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class JapaneseQualityGuard {

	public QualityResult inspect(String normalizedText, ScriptGenerationContext context) {
		List<String> safetyFlags = new ArrayList<>();
		String guarded = normalizedText == null ? "" : normalizedText;
		if (guarded.matches("(?i).*ignore previous instructions.*") || guarded.contains("命令を無視")) {
			guarded = guarded.replaceAll("(?i)ignore previous instructions", " ");
			guarded = guarded.replace("命令を無視", " ");
			safetyFlags.add("PROMPT_INJECTION_REMOVED");
		}
		if (context.letter() != null) {
			safetyFlags.add("LETTER_SOURCE");
		}
		if (guarded.length() > 400) {
			guarded = guarded.substring(0, 400) + "。";
			safetyFlags.add("TRUNCATED");
		}
		return new QualityResult(guarded.replaceAll("\\s+", " ").trim(), List.copyOf(safetyFlags));
	}

	public record QualityResult(String text, List<String> safetyFlags) {
	}
}
