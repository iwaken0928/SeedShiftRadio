package com.seedshiftradio.radio;

import java.text.Normalizer;

import org.springframework.stereotype.Service;

@Service
public class JapaneseScriptNormalizer {

	private static final String EMOJI_CONTROL_CHARACTERS = "[\\p{So}\\p{Sk}\\p{Cf}\\x{FE0F}\\p{Cn}]";

	public String normalize(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
		normalized = normalized.replaceAll("https?://\\S+", "リンク");
		normalized = normalized.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ");
		normalized = normalized.replaceAll(EMOJI_CONTROL_CHARACTERS, " ");
		normalized = normalized.replaceAll("[!！]{2,}", "！");
		normalized = normalized.replaceAll("[?？]{2,}", "？");
		normalized = normalized.replaceAll("\\s+", " ").trim();
		return normalized;
	}
}
