package com.seedshiftradio.settings;

public record MusicGenerationRequest(
		String requestId,
		String stationId,
		String purpose,
		String mode,
		String prompt,
		String lyrics,
		String lyricsLanguage,
		Integer durationSeconds,
		Integer bpm,
		String keyScale,
		String timeSignature,
		Integer seed,
		String modelProfileId,
		String outputFormat) {

	public MusicGenerationRequest normalize(SettingsDocument.MusicGenerationModelProfile profile) {
		SettingsDocument.MusicGenerationModelProfile normalizedProfile = profile == null
				? SettingsDocument.MusicGenerationModelProfile.aceJaFast().normalize()
				: profile.normalize();
		int requestedDuration = durationSeconds == null ? 30 : durationSeconds;
		int maxDuration = normalizedProfile.maxDurationSeconds() == null ? 120 : normalizedProfile.maxDurationSeconds();
		return new MusicGenerationRequest(
				blankToDefault(requestId, "music-request"),
				blankToDefault(stationId, "unknown-station"),
				blankToDefault(purpose, "radio"),
				blankToDefault(mode, "JAPANESE_SONG"),
				blankToDefault(prompt, "Japanese original radio song"),
				lyrics == null ? "" : lyrics,
				blankToDefault(lyricsLanguage, normalizedProfile.lyricsLanguage()),
				Math.max(10, Math.min(maxDuration, requestedDuration)),
				bpm,
				keyScale == null ? "" : keyScale,
				timeSignature == null || timeSignature.isBlank() ? "4" : timeSignature,
				seed,
				blankToDefault(modelProfileId, "ace-ja-fast"),
				blankToDefault(outputFormat, normalizedProfile.outputFormat()));
	}

	private static String blankToDefault(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
