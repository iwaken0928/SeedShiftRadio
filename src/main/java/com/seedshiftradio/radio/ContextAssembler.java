package com.seedshiftradio.radio;

import org.springframework.stereotype.Service;

import com.seedshiftradio.letter.LetterEntity;
import com.seedshiftradio.letter.LetterRepository;
import com.seedshiftradio.station.PersonalityEntity;
import com.seedshiftradio.station.PersonalityRepository;
import com.seedshiftradio.station.StationEntity;
import com.seedshiftradio.station.StationRepository;
import com.seedshiftradio.station.VoiceProfileEntity;
import com.seedshiftradio.station.VoiceProfileRepository;

@Service
public class ContextAssembler {

	private final StationRepository stationRepository;
	private final PersonalityRepository personalityRepository;
	private final VoiceProfileRepository voiceProfileRepository;
	private final LetterRepository letterRepository;
	private final PromptComposer promptComposer;

	public ContextAssembler(
			StationRepository stationRepository,
			PersonalityRepository personalityRepository,
			VoiceProfileRepository voiceProfileRepository,
			LetterRepository letterRepository,
			PromptComposer promptComposer) {
		this.stationRepository = stationRepository;
		this.personalityRepository = personalityRepository;
		this.voiceProfileRepository = voiceProfileRepository;
		this.letterRepository = letterRepository;
		this.promptComposer = promptComposer;
	}

	public ScriptGenerationContext assemble(PlayoutSessionEntity session, QueueItemEntity item) {
		StationEntity station = stationRepository.findById(session.getStationId()).orElse(null);
		PersonalityEntity personality = resolvePersonality(station);
		VoiceProfileEntity voiceProfile = resolveVoiceProfile(station);
		LetterEntity letter = item.getLetterId() == null || item.getLetterId().isBlank()
				? null
				: letterRepository.findById(item.getLetterId()).orElse(null);
		return new ScriptGenerationContext(
				session,
				item,
				station,
				personality,
				voiceProfile,
				letter,
				promptComposer.compose(session, item));
	}

	private PersonalityEntity resolvePersonality(StationEntity station) {
		if (station == null || station.getLanguagePersonaId() == null) {
			return null;
		}
		return personalityRepository.findById(station.getLanguagePersonaId()).orElse(null);
	}

	private VoiceProfileEntity resolveVoiceProfile(StationEntity station) {
		if (station == null || station.getDefaultVoiceProfileId() == null) {
			return null;
		}
		return voiceProfileRepository.findById(station.getDefaultVoiceProfileId()).orElse(null);
	}
}
