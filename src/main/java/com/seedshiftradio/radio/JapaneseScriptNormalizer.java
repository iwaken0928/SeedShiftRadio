package com.seedshiftradio.radio;

import java.text.Normalizer;

import org.springframework.stereotype.Service;

@Service
public class JapaneseScriptNormalizer {

	public String normalize(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
		normalized = normalized.replaceAll("https?://\\S+", "リンク");
		normalized = normalized.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ");
		normalized = normalized.replaceAll("[\\p{So}\\p{Cn}]", " ");
		normalized = normalized.replaceAll("[!！]{2,}", "！");
		normalized = normalized.replaceAll("[?？]{2,}", "？");
		normalized = normalized.replaceAll("\\s+", " ").trim();
		return normalized;
	}
}
