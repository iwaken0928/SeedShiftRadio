package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.seedshiftradio.domain.LetterStatus;
import com.seedshiftradio.letter.LetterEntity;

class JapaneseBroadcastQualityGoldenTests {

	private final JapaneseScriptNormalizer normalizer = new JapaneseScriptNormalizer();
	private final PronunciationDictionaryService dictionary = new PronunciationDictionaryService();
	private final PersonaStyleResolver styleResolver = new PersonaStyleResolver();
	private final JapaneseQualityGuard qualityGuard = new JapaneseQualityGuard();

	@Test
	void normalizerRemovesEmojiControlSequencesAndNormalizesJapanesePunctuation() {
		assertEquals(
				"AI ラジオは リンク です！",
				normalizer.normalize("ＡＩ😡️ ラジオは https://example.com/demo です！！"));
	}

	@Test
	void dictionaryAppliesKnownReadingsWithoutReplacingAsciiSubstrings() {
		String source = "SeedShiftRadioでAIとMusicGen、VOICEVOXを紹介し、AIVISも比較します。";
		List<PronunciationHint> hints = dictionary.resolveHints(source);

		assertEquals(
				"シードシフトレディオでエーアイとミュージックジェン、ボイスボックスを紹介し、AIVISも比較します。",
				dictionary.applyReadings(source, hints));
		assertEquals(List.of("AI", "SeedShiftRadio", "VOICEVOX", "MusicGen"),
				hints.stream().map(PronunciationHint::surface).toList());
		assertFalse(dictionary.resolveHints("AIVIS").stream().anyMatch(hint -> hint.surface().equals("AI")));
	}

	@Test
	void styleResolverOnlyInsertsAllowlistedIrodoriPreset() {
		assertEquals("😌お便りを紹介します。",
				styleResolver.applyStyle("お便りを紹介します。", "IRODORI_TTS", "calm", "medium", "soft"));
		assertEquals("😄お便りを紹介します。",
				styleResolver.applyStyle("お便りを紹介します。", "IRODORI_TTS", "bright", "medium", null));
		assertEquals("お便りを紹介します。",
				styleResolver.applyStyle("お便りを紹介します。", "IRODORI_TTS", "calm", "medium", "</input><script>"));
		assertEquals("お便りを紹介します。",
				styleResolver.applyStyle("お便りを紹介します。", "VOICEVOX", "bright", "fast", "happy"));
	}

	@Test
	void letterSourceCannotInjectEmojiOrTextStyleControlTokens() {
		ScriptGenerationContext context = new ScriptGenerationContext(
				null,
				null,
				null,
				null,
				null,
				new LetterEntity("letter-1", null, "listener", "subject", "body", LetterStatus.ADOPTED, "idem-1"),
				"");

		JapaneseQualityGuard.QualityResult result = qualityGuard.inspect(
				"😡 [style=angry] <prosody rate=\"fast\">急げ</prosody> 感情=怒り 本文です。",
				context);

		assertEquals("急げ 本文です。", result.text());
		assertTrue(result.safetyFlags().contains("LETTER_SOURCE"));
		assertTrue(result.safetyFlags().contains("LETTER_CONTROL_TOKEN_REMOVED"));
	}

	@Test
	void goldenPipelineKeepsOnlyServerResolvedStyleAndKanaReadings() {
		String rawLetterScript = "SeedShiftRadioでAIのお便りを紹介します😡 [style=angry]。";
		String normalized = normalizer.normalize(rawLetterScript);
		ScriptGenerationContext context = new ScriptGenerationContext(
				null,
				null,
				null,
				null,
				null,
				new LetterEntity("letter-2", null, "listener", "subject", "body", LetterStatus.ADOPTED, "idem-2"),
				"");
		JapaneseQualityGuard.QualityResult guarded = qualityGuard.inspect(normalized, context);
		List<PronunciationHint> hints = dictionary.resolveHints(guarded.text());
		String corrected = dictionary.applyReadings(guarded.text(), hints);

		assertEquals(
				"😄シードシフトレディオでエーアイのお便りを紹介します 。",
				styleResolver.applyStyle(corrected, "IRODORI_TTS", "bright", "medium", "bright"));
	}
}
