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
		if (context != null && context.letter() != null) {
			safetyFlags.add("LETTER_SOURCE");
		}
		String masked = maskPersonalInformation(guarded);
		if (!masked.equals(guarded)) {
			guarded = masked;
			safetyFlags.add("PERSONAL_INFO_MASKED");
		}
		if (guarded.length() > 400) {
			guarded = guarded.substring(0, 400) + "。";
			safetyFlags.add("TRUNCATED");
		}
		return new QualityResult(guarded.replaceAll("\\s+", " ").trim(), List.copyOf(safetyFlags));
	}

	private String maskPersonalInformation(String value) {
		return value
				.replaceAll("https?://\\S+|www\\.\\S+", "URLを伏せた情報")
				.replaceAll("(?i)\\b[\\w.%+-]+@[\\w.-]+\\.[a-z]{2,}\\b", "メールアドレスを伏せた情報")
				.replaceAll("(?<!\\d)0\\d{1,4}[-ー−]?\\d{1,4}[-ー−]?\\d{3,4}(?!\\d)", "電話番号を伏せた情報")
				.replaceAll("[\\p{IsHan}ぁ-んァ-ン]{2,}(?:都|道|府|県)[\\p{IsHan}ぁ-んァ-ン0-9０-９\\-ー−の]{2,}(?:市|区|町|村)[\\p{IsHan}ぁ-んァ-ン0-9０-９\\-ー−の]*", "住所を伏せた情報");
	}

	public record QualityResult(String text, List<String> safetyFlags) {
	}
}
