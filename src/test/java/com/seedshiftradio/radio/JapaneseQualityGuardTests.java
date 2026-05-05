package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JapaneseQualityGuardTests {

	private final JapaneseQualityGuard qualityGuard = new JapaneseQualityGuard();

	@Test
	void inspectMasksPersonalInformationCandidates() {
		JapaneseQualityGuard.QualityResult result = qualityGuard.inspect(
				"連絡先は test@example.com と 090-1234-5678、https://example.com/profile です。",
				null);

		assertFalse(result.text().contains("test@example.com"));
		assertFalse(result.text().contains("090-1234-5678"));
		assertFalse(result.text().contains("https://example.com/profile"));
		assertTrue(result.safetyFlags().contains("PERSONAL_INFO_MASKED"));
	}
}
