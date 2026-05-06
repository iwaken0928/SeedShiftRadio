package com.seedshiftradio.radio;

import org.springframework.stereotype.Service;

@Service
public class SentenceSplitter {

	private static final int MAX_SENTENCE_LENGTH = 60;

	public String splitLongSentences(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		StringBuilder builder = new StringBuilder(text.length() + 16);
		int lengthSinceBreak = 0;
		for (int index = 0; index < text.length(); index++) {
			char current = text.charAt(index);
			builder.append(current);
			lengthSinceBreak++;
			if (current == '。' || current == '！' || current == '？') {
				lengthSinceBreak = 0;
				continue;
			}
			if (lengthSinceBreak >= MAX_SENTENCE_LENGTH) {
				builder.append('。');
				lengthSinceBreak = 0;
			}
		}
		return builder.toString();
	}
}
