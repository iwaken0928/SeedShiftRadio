package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.domain.ProviderType;

class ProviderErrorClassifierTests {

	@ParameterizedTest
	@CsvSource({
			"401, PROVIDER_AUTH_FAILED",
			"403, PROVIDER_AUTH_FAILED",
			"408, PROVIDER_TIMEOUT",
			"504, PROVIDER_TIMEOUT",
			"429, PROVIDER_RESOURCE_EXHAUSTED",
			"503, PROVIDER_RESOURCE_EXHAUSTED",
			"400, PROVIDER_REJECTED",
			"422, PROVIDER_REJECTED",
			"500, PROVIDER_BAD_RESPONSE",
			"502, PROVIDER_BAD_RESPONSE"
	})
	void classifiesHttpStatusIntoCanonicalErrorCode(int statusCode, ProviderErrorCode expected) {
		assertEquals(expected, ProviderErrorClassifier.fromHttpStatus(statusCode));
	}

	@ParameterizedTest
	@CsvSource({
			"PROVIDER_TIMEOUT, PROVIDER_TIMEOUT",
			"provider_auth_failed, PROVIDER_AUTH_FAILED",
			"VOICE_REF_NOT_FOUND, VOICE_REF_NOT_FOUND",
			"WORKER_PRIVATE_ERROR, PROVIDER_BAD_RESPONSE",
			"'  ', PROVIDER_BAD_RESPONSE"
	})
	void normalizesExternalCodeWithoutPassingThroughUnknownValues(String externalCode, ProviderErrorCode expected) {
		assertEquals(expected, ProviderErrorClassifier.normalizeExternalCode(externalCode));
	}

	@ParameterizedTest
	@EnumSource(value = ProviderErrorCode.class, names = {
			"PROVIDER_UNREACHABLE",
			"PROVIDER_TIMEOUT",
			"PROVIDER_BAD_RESPONSE",
			"PROVIDER_RESOURCE_EXHAUSTED",
			"VOICE_REF_NOT_FOUND",
			"VOICE_CONSENT_REQUIRED"
	})
	void allowsFallbackOnlyForRecoverableFailures(ProviderErrorCode errorCode) {
		assertTrue(ProviderErrorClassifier.fallbackAllowed(ProviderType.TTS, errorCode));
	}

	@ParameterizedTest
	@EnumSource(value = ProviderErrorCode.class, names = {
			"PROVIDER_REJECTED",
			"PROVIDER_AUTH_FAILED",
			"PROVIDER_INTERRUPTED"
	})
	void rejectsFallbackForNonRecoverableFailures(ProviderErrorCode errorCode) {
		assertFalse(ProviderErrorClassifier.fallbackAllowed(ProviderType.TTS, errorCode));
	}

	@ParameterizedTest
	@EnumSource(value = ProviderErrorCode.class, names = {
			"VOICE_REF_NOT_FOUND",
			"VOICE_CONSENT_REQUIRED"
	})
	void doesNotApplyTtsSpecificFallbackCodesToMusicGeneration(ProviderErrorCode errorCode) {
		assertFalse(ProviderErrorClassifier.fallbackAllowed(ProviderType.MUSIC, errorCode));
	}
}
