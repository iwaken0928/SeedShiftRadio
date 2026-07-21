package com.seedshiftradio.settings;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TtsRuntimeProfile(
		String voiceProfileId,
		String stationId,
		String providerKey,
		String engineType,
		String speakerKey,
		String styleKey,
		BigDecimal speed,
		Map<String, Object> providerOptions,
		String referenceVoiceRef,
		String consentPolicyRef) {

	public TtsRuntimeProfile {
		providerOptions = providerOptions == null
				? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(providerOptions));
	}
}
