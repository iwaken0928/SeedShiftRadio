package com.seedshiftradio.radio;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class JapaneseQualityGuard {

	private static final Pattern TTS_CONTROL_TOKEN = Pattern.compile(
			"(?i)</?(?:speak|prosody|break|emphasis|voice|style|emotion|tempo)[^>]*>"
					+ "|[\\[\\{]\\s*(?:style|emotion|speaker|voice|tempo|speed|pause|スタイル|感情|話速|声)\\s*[:=][^\\]\\}]*[\\]\\}]"
					+ "|\\b(?:style|emotion|speaker|voice|tempo|speed|pause)\\s*[:=]\\s*[A-Za-z0-9_.-]+"
					+ "|(?:スタイル|感情|話速|声)\\s*[:=]\\s*[\\p{L}\\p{N}_.-]+");
	private static final Pattern EMOJI_CONTROL_CHARACTERS = Pattern.compile("[\\p{So}\\p{Sk}\\p{Cf}\\x{FE0F}\\p{Cn}]");

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
		String withoutControlTokens = removeTtsControlTokens(guarded);
		if (!withoutControlTokens.equals(guarded)) {
			guarded = withoutControlTokens;
			safetyFlags.add(context != null && context.letter() != null
					? "LETTER_CONTROL_TOKEN_REMOVED"
					: "TTS_CONTROL_TOKEN_REMOVED");
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

	private String removeTtsControlTokens(String value) {
		String withoutTextTokens = TTS_CONTROL_TOKEN.matcher(value).replaceAll(" ");
		return EMOJI_CONTROL_CHARACTERS.matcher(withoutTextTokens).replaceAll(" ");
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
