package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.radio.PlayoutSessionEntity;
import com.seedshiftradio.radio.PlayoutSessionRepository;
import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.station.StationEntity;
import com.seedshiftradio.station.StationRepository;
import com.seedshiftradio.station.VoiceProfileEntity;
import com.seedshiftradio.station.VoiceProfileRepository;

class TtsRuntimeProfileResolverTests {

	PlayoutSessionRepository sessionRepository = mock(PlayoutSessionRepository.class);
	StationRepository stationRepository = mock(StationRepository.class);
	VoiceProfileRepository voiceProfileRepository = mock(VoiceProfileRepository.class);
	TtsRuntimeProfileResolver resolver;
	QueueItemEntity item = mock(QueueItemEntity.class);
	PlayoutSessionEntity session = mock(PlayoutSessionEntity.class);
	StationEntity station = mock(StationEntity.class);
	VoiceProfileEntity profile = mock(VoiceProfileEntity.class);

	@BeforeEach
	void setUp() {
		resolver = new TtsRuntimeProfileResolver(sessionRepository, stationRepository, voiceProfileRepository);
		when(item.getSessionId()).thenReturn("session-night");
		when(sessionRepository.findById("session-night")).thenReturn(Optional.of(session));
		when(session.getStationId()).thenReturn("station-night");
		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station));
		when(station.getId()).thenReturn("station-night");
		when(station.getDefaultVoiceProfileId()).thenReturn("voice-night");
		when(voiceProfileRepository.findById("voice-night")).thenReturn(Optional.of(profile));
		when(profile.getId()).thenReturn("voice-night");
		when(profile.getScope()).thenReturn("STATION");
		when(profile.getStationId()).thenReturn("station-night");
		when(profile.getProviderKey()).thenReturn("irodori");
		when(profile.getEngineType()).thenReturn("IRODORI_TTS");
		when(profile.getSpeakerKey()).thenReturn("night-main");
		when(profile.getStyleKey()).thenReturn("calm");
		when(profile.getSpeed()).thenReturn(new BigDecimal("1.10"));
		when(profile.getProviderOptions()).thenReturn(Map.of("responseFormat", "wav"));
	}

	@Test
	void resolvesStationScopedProfileForOwningStation() {
		TtsRuntimeProfile runtimeProfile = resolver.resolve(item).orElseThrow();

		assertEquals("station-night", runtimeProfile.stationId());
		assertEquals("irodori", runtimeProfile.providerKey());
		assertEquals(new BigDecimal("1.10"), runtimeProfile.speed());
	}

	@Test
	void rejectsProfileOwnedByAnotherStationWithoutExposingIdentifiers() {
		when(profile.getStationId()).thenReturn("station-private-owner");

		TtsSynthesisException exception = assertThrows(TtsSynthesisException.class, () -> resolver.resolve(item));

		assertEquals(ProviderErrorCode.PROVIDER_REJECTED, exception.providerErrorCode());
		assertFalse(exception.getMessage().contains("station-private-owner"));
	}

	@Test
	void rejectsReferenceVoiceWithoutConsentBeforeProviderCall() {
		when(profile.getReferenceVoiceRef()).thenReturn("voices/private-reference.wav");
		when(profile.getConsentPolicyRef()).thenReturn(null);

		TtsSynthesisException exception = assertThrows(TtsSynthesisException.class, () -> resolver.resolve(item));

		assertEquals(ProviderErrorCode.VOICE_CONSENT_REQUIRED, exception.providerErrorCode());
		assertFalse(exception.getMessage().contains("private-reference"));
	}

	@Test
	void rejectsUnsafeReferenceVoiceWithoutExposingRawPath() {
		when(profile.getReferenceVoiceRef()).thenReturn("../private/reference.wav");
		when(profile.getConsentPolicyRef()).thenReturn("consent-approved");

		TtsSynthesisException exception = assertThrows(TtsSynthesisException.class, () -> resolver.resolve(item));

		assertEquals(ProviderErrorCode.VOICE_REF_NOT_FOUND, exception.providerErrorCode());
		assertFalse(exception.getMessage().contains("../private"));
	}
}
