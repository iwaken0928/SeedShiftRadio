package com.seedshiftradio.station;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.programming.ProgramTemplateRepository;
import com.seedshiftradio.programming.StationProgrammingPolicyRepository;

@ExtendWith(MockitoExtension.class)
class StationAdminServiceTests {

	private static final String TARGET_STATION_ID = "station-target";
	private static final String VOICE_PROFILE_ID = "voice-main";

	@Mock
	StationRepository stationRepository;

	@Mock
	PersonalityRepository personalityRepository;

	@Mock
	VoiceProfileRepository voiceProfileRepository;

	@Mock
	ProgramTemplateRepository programTemplateRepository;

	@Mock
	StationProgrammingPolicyRepository policyRepository;

	StationAdminService stationAdminService;

	@BeforeEach
	void setUp() {
		stationAdminService = new StationAdminService(
				stationRepository,
				personalityRepository,
				voiceProfileRepository,
				programTemplateRepository,
				policyRepository);
	}

	@Test
	void globalVoiceProfileCanBeAssignedToAnyStation() {
		VoiceProfileEntity voiceProfile = voiceProfile("GLOBAL", null, null, null);
		stubStationCreation(voiceProfile);
		allowStationSave();

		StationDtos.StationResponse response = assertDoesNotThrow(
				() -> stationAdminService.createStation(request()));

		assertEquals(VOICE_PROFILE_ID, response.defaultVoiceProfileId());
		verify(stationRepository).save(any(StationEntity.class));
	}

	@Test
	void stationVoiceProfileCanBeAssignedToOwningStation() {
		VoiceProfileEntity voiceProfile = voiceProfile("STATION", TARGET_STATION_ID, null, null);
		stubStationCreation(voiceProfile);
		allowStationSave();

		assertDoesNotThrow(() -> stationAdminService.createStation(request()));

		verify(stationRepository).save(any(StationEntity.class));
	}

	@Test
	void stationVoiceProfileCannotBeAssignedToDifferentStation() {
		VoiceProfileEntity voiceProfile = voiceProfile("STATION", "station-owner", null, null);
		stubStationCreation(voiceProfile);

		ApiException exception = assertThrows(
				ApiException.class,
				() -> stationAdminService.createStation(request()));

		assertSafeVoiceProfileDetails(exception);
		assertFalse(exception.getMessage().contains("station-owner"));
	}

	@Test
	void referenceVoiceRequiresConsentPolicy() {
		VoiceProfileEntity voiceProfile = voiceProfile("GLOBAL", null, "voices/private/Alice-reference.wav", null);
		stubStationCreation(voiceProfile);

		ApiException exception = assertThrows(
				ApiException.class,
				() -> stationAdminService.createStation(request()));

		assertSafeVoiceProfileDetails(exception);
		assertFalse(exception.getMessage().contains("Alice"));
	}

	@ParameterizedTest
	@MethodSource("invalidReferenceVoiceRefs")
	void absoluteUrlAndParentTraversalReferenceVoiceRefsAreRejectedWithoutLeakingSensitiveValues(
			String referenceVoiceRef,
			String sensitiveFragment) {
		VoiceProfileEntity voiceProfile = voiceProfile(
				"GLOBAL",
				null,
				referenceVoiceRef,
				"consent:voice-main-2026-07");
		stubStationCreation(voiceProfile);

		ApiException exception = assertThrows(
				ApiException.class,
				() -> stationAdminService.createStation(request()));

		assertSafeVoiceProfileDetails(exception);
		String detailsText = exception.getDetails().toString();
		assertFalse(detailsText.contains(referenceVoiceRef));
		assertFalse(detailsText.contains(sensitiveFragment));
		assertFalse(exception.getMessage().contains(referenceVoiceRef));
		assertFalse(exception.getMessage().contains(sensitiveFragment));
	}

	private void stubStationCreation(VoiceProfileEntity voiceProfile) {
		when(stationRepository.existsById(TARGET_STATION_ID)).thenReturn(false);
		when(stationRepository.findAll()).thenReturn(java.util.List.of());
		when(personalityRepository.existsById("persona-main")).thenReturn(true);
		when(voiceProfileRepository.findById(VOICE_PROFILE_ID)).thenReturn(Optional.of(voiceProfile));
	}

	private void allowStationSave() {
		when(stationRepository.save(any(StationEntity.class)))
				.thenAnswer(invocation -> invocation.getArgument(0, StationEntity.class));
	}

	private StationDtos.StationUpsertRequest request() {
		return new StationDtos.StationUpsertRequest(
				null,
				TARGET_STATION_ID,
				"テスト局",
				new BigDecimal("88.5"),
				"talk",
				"persona-main",
				VOICE_PROFILE_ID,
				true,
				true,
				null);
	}

	private VoiceProfileEntity voiceProfile(
			String scope,
			String stationId,
			String referenceVoiceRef,
			String consentPolicyRef) {
		VoiceProfileEntity voiceProfile = new VoiceProfileEntity();
		voiceProfile.setId(VOICE_PROFILE_ID);
		voiceProfile.setEngineType("IRODORI_TTS");
		voiceProfile.setSpeakerKey("main");
		voiceProfile.setStyleKey("calm");
		voiceProfile.setSpeed(BigDecimal.ONE);
		voiceProfile.setPitch(BigDecimal.ZERO);
		voiceProfile.setPlaybackMode(PlaybackMode.SERVER_AUDIO);
		voiceProfile.setScope(scope);
		voiceProfile.setStationId(stationId);
		voiceProfile.setProviderKey("irodori");
		voiceProfile.setProviderOptions(Map.of("responseFormat", "wav"));
		voiceProfile.setReferenceVoiceRef(referenceVoiceRef);
		voiceProfile.setConsentPolicyRef(consentPolicyRef);
		return voiceProfile;
	}

	private void assertSafeVoiceProfileDetails(ApiException exception) {
		assertEquals(Map.of("voiceProfileId", VOICE_PROFILE_ID), exception.getDetails());
	}

	private static Stream<Arguments> invalidReferenceVoiceRefs() {
		return Stream.of(
				Arguments.of("C:\\Users\\Alice\\voices\\reference.wav", "Alice"),
				Arguments.of("/srv/voices/Alice/reference.wav", "Alice"),
				Arguments.of("https://voices.example.test/Alice/reference.wav", "Alice"),
				Arguments.of("file:/srv/voices/Alice-reference.wav", "Alice"),
				Arguments.of("data:audio/wav;base64,Alice", "Alice"),
				Arguments.of(" voices/Alice-reference.wav", "Alice"),
				Arguments.of("../voices/Alice-reference.wav", "Alice"),
				Arguments.of("voices/../../Alice-reference.wav", "Alice"));
	}
}
