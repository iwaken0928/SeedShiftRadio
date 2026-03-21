package com.seedshiftradio.radio;

import java.time.Instant;
import java.util.List;

import com.seedshiftradio.domain.PlaybackMode;

public record ClientCapabilitiesRecord(
		String clientId,
		String clientType,
		boolean supportsClientSideTts,
		List<String> supportedVoiceEngines,
		PlaybackMode preferredPlaybackMode,
		List<ClientCapabilitiesRequest.LocalVoiceProfile> localVoiceProfiles,
		Instant acceptedAt) {
}
