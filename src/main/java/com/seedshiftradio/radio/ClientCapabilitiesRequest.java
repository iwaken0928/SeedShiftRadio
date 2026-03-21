package com.seedshiftradio.radio;

import java.util.List;

import com.seedshiftradio.domain.PlaybackMode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ClientCapabilitiesRequest(
		@NotBlank String clientId,
		@NotBlank String clientType,
		boolean supportsClientSideTts,
		List<String> supportedVoiceEngines,
		@NotNull PlaybackMode preferredPlaybackMode,
		List<LocalVoiceProfile> localVoiceProfiles) {

	public record LocalVoiceProfile(String engine, String profileKey) {
	}
}
