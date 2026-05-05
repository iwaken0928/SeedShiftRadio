package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.station.PersonalityEntity;
import com.seedshiftradio.station.PersonalityRepository;
import com.seedshiftradio.station.StationEntity;
import com.seedshiftradio.station.StationRepository;
import com.seedshiftradio.station.VoiceProfileEntity;
import com.seedshiftradio.station.VoiceProfileRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpeechDirectiveAssemblerTests {

	@Mock
	StationRepository stationRepository;

	@Mock
	PersonalityRepository personalityRepository;

	@Mock
	VoiceProfileRepository voiceProfileRepository;

	@Mock
	ClientCapabilitiesService clientCapabilitiesService;

	SpeechDirectiveAssembler assembler;

	@BeforeEach
	void setUp() {
		assembler = new SpeechDirectiveAssembler(
				stationRepository,
				personalityRepository,
				voiceProfileRepository,
				clientCapabilitiesService);
	}

	@Test
	void assembleUsesRegisteredClientVoiceHintWhenClientSideTtsIsPreferred() {
		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setId("playout-001");
		session.setStationId("station-night");
		session.setCorrelationId("corr-001");

		QueueItemEntity item = new QueueItemEntity();
		item.setId("queue-001");
		item.setSpeechDirectiveId("sd-queue-001");
		item.setSlotRole(SlotRole.OPENING);
		item.setSegmentType(SegmentType.TALK);
		item.setStatus(QueueItemStatus.READY);
		item.setTitle("オープニング");

		StationEntity station = new StationEntity(
				"station-night",
				"Midnight Echo",
				new BigDecimal("81.3"),
				"talk",
				"persona-night-main",
				"voice-night-main",
				true,
				"tmpl-night-regular",
				true);
		PersonalityEntity personality = mock(PersonalityEntity.class);
		when(personality.getId()).thenReturn("persona-night-main");
		when(personality.getDisplayName()).thenReturn("Echo");
		when(personality.getLanguageTone()).thenReturn("calm");
		VoiceProfileEntity voiceProfile = mock(VoiceProfileEntity.class);
		when(voiceProfile.getId()).thenReturn("voice-night-main");
		when(voiceProfile.getEngineType()).thenReturn("VOICEVOX");
		when(voiceProfile.getSpeakerKey()).thenReturn("4");
		when(voiceProfile.getStyleKey()).thenReturn("normal");
		when(voiceProfile.getPlaybackMode()).thenReturn(PlaybackMode.SERVER_AUDIO);

		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station));
		when(personalityRepository.findById("persona-night-main")).thenReturn(Optional.of(personality));
		when(voiceProfileRepository.findById("voice-night-main")).thenReturn(Optional.of(voiceProfile));
		when(clientCapabilitiesService.latest("desktop-win-main")).thenReturn(new ClientCapabilitiesRecord(
				"desktop-win-main",
				"CSHARP_NATIVE",
				true,
				List.of("VOICEVOX", "VOICEROID"),
				PlaybackMode.CLIENT_TTS,
				List.of(new ClientCapabilitiesRequest.LocalVoiceProfile("VOICEROID", "yukari-main")),
				Instant.parse("2026-03-20T09:00:00Z")));

		SpeechDirectiveResponse directive = assembler.assemble(session, item, "desktop-win-main");

		assertEquals("sd-queue-001", directive.id());
		assertEquals("persona-night-main", directive.personaRef());
		assertEquals("VOICEROID:yukari-main", directive.voiceHint());
		assertEquals("corr-001", directive.correlationId());
	}

	@Test
	void assembleFallsBackToDefaultVoiceProfileWhenClientCapabilityIsMissing() {
		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setStationId("station-night");
		session.setCorrelationId("corr-002");

		QueueItemEntity item = new QueueItemEntity();
		item.setId("queue-002");
		item.setSlotRole(SlotRole.TOPIC);
		item.setSegmentType(SegmentType.TALK);
		item.setStatus(QueueItemStatus.READY);
		item.setTitle("トーク");

		StationEntity station = new StationEntity(
				"station-night",
				"Midnight Echo",
				new BigDecimal("81.3"),
				"talk",
				"persona-night-main",
				"voice-night-main",
				true,
				"tmpl-night-regular",
				true);
		PersonalityEntity personality = mock(PersonalityEntity.class);
		when(personality.getId()).thenReturn("persona-night-main");
		when(personality.getDisplayName()).thenReturn("Echo");
		when(personality.getLanguageTone()).thenReturn("calm");
		VoiceProfileEntity voiceProfile = mock(VoiceProfileEntity.class);
		when(voiceProfile.getId()).thenReturn("voice-night-main");
		when(voiceProfile.getEngineType()).thenReturn("VOICEVOX");
		when(voiceProfile.getSpeakerKey()).thenReturn("4");
		when(voiceProfile.getStyleKey()).thenReturn("normal");
		when(voiceProfile.getPlaybackMode()).thenReturn(PlaybackMode.SERVER_AUDIO);

		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station));
		when(personalityRepository.findById("persona-night-main")).thenReturn(Optional.of(personality));
		when(voiceProfileRepository.findById("voice-night-main")).thenReturn(Optional.of(voiceProfile));
		when(clientCapabilitiesService.latest("missing-client")).thenReturn(null);

		SpeechDirectiveResponse directive = assembler.assemble(session, item, "missing-client");

		assertEquals("VOICEVOX:4:normal", directive.voiceHint());
		assertEquals("calm", directive.emotion());
		assertEquals("medium", directive.tempo());
	}
}
