package com.seedshiftradio.radio;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.seedshiftradio.letter.LetterEntity;

final class LetterBroadcastContentFactory {

	private static final int MAX_SUBJECT_LENGTH = 40;
	private static final int MAX_SUMMARY_LENGTH = 120;
	private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[。！？!?\\r\\n]+");
	private static final Pattern PROMPT_INSTRUCTION = Pattern.compile(
			"(?i)(?:ignore\\s+(?:all\\s+)?(?:previous|prior)\\s+instructions?"
					+ "|system\\s+prompt|developer\\s+(?:message|instructions?)|follow\\s+these\\s+instructions?"
					+ "|you\\s+are\\s+(?:now|a)|(?:命令|指示).{0,16}(?:従|無視|実行|変更|上書き)"
					+ "|(?:設定|ルール|役割|プロンプト).{0,16}(?:変更|無視|上書き|開示))");
	private static final Pattern TTS_CONTROL_TOKEN = Pattern.compile(
			"(?i)</?(?:speak|prosody|break|emphasis|voice|style|emotion|tempo)[^>]*>"
					+ "|[\\[\\{]\\s*(?:style|emotion|speaker|voice|tempo|speed|pause|スタイル|感情|話速|声)\\s*[:=][^\\]\\}]*[\\]\\}]"
					+ "|\\b(?:style|emotion|speaker|voice|tempo|speed|pause)\\s*[:=]\\s*[A-Za-z0-9_.-]+"
					+ "|(?:スタイル|感情|話速|声)\\s*[:=]\\s*[\\p{L}\\p{N}_.-]+");
	private static final Pattern EMOJI_CONTROL_CHARACTERS = Pattern.compile("[\\p{So}\\p{Sk}\\p{Cf}\\x{FE0F}\\p{Cn}]");

	private LetterBroadcastContentFactory() {
	}

	static LetterBroadcastContent from(LetterEntity letter) {
		if (letter == null) {
			return new LetterBroadcastContent("近況", "番組へのメッセージ", null);
		}
		String subject = cleanText(letter.getSubject());
		if (PROMPT_INSTRUCTION.matcher(subject).find()) {
			subject = "";
		}
		String summary = summarize(letter.getBody());
		return new LetterBroadcastContent(
				subject.isBlank() ? "近況" : truncate(subject, MAX_SUBJECT_LENGTH),
				summary.isBlank() ? "番組へのメッセージ" : truncate(summary, MAX_SUMMARY_LENGTH),
				letter.getId());
	}

	private static String summarize(String body) {
		List<String> safeSentences = new ArrayList<>();
		for (String sentence : SENTENCE_BOUNDARY.split(body == null ? "" : body)) {
			String cleaned = cleanText(sentence);
			if (!cleaned.isBlank() && !PROMPT_INSTRUCTION.matcher(cleaned).find()) {
				safeSentences.add(cleaned);
			}
		}
		return String.join("。", safeSentences);
	}

	private static String cleanText(String value) {
		String cleaned = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC);
		cleaned = TTS_CONTROL_TOKEN.matcher(cleaned).replaceAll(" ");
		cleaned = EMOJI_CONTROL_CHARACTERS.matcher(cleaned).replaceAll(" ");
		cleaned = cleaned
				.replaceAll("https?://\\S+|www\\.\\S+", "URLを伏せた情報")
				.replaceAll("(?i)\\b[\\w.%+-]+@[\\w.-]+\\.[a-z]{2,}\\b", "メールアドレスを伏せた情報")
				.replaceAll("(?<!\\d)0\\d{1,4}[-ー−]?\\d{1,4}[-ー−]?\\d{3,4}(?!\\d)", "電話番号を伏せた情報")
				.replaceAll("[\\p{IsHan}ぁ-んァ-ン]{2,}(?:都|道|府|県)[\\p{IsHan}ぁ-んァ-ン0-9０-９\\-ー−の]{2,}(?:市|区|町|村)[\\p{IsHan}ぁ-んァ-ン0-9０-９\\-ー−の]*", "住所を伏せた情報")
				.replaceAll("[<>\\[\\]{}]", " ")
				.replaceAll("\\s+", " ")
				.trim();
		return cleaned;
	}

	private static String truncate(String value, int maxLength) {
		if (value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength).stripTrailing() + "…";
	}
}
