package com.seedshiftradio.radio;

import com.seedshiftradio.letter.LetterEntity;
import com.seedshiftradio.station.PersonalityEntity;
import com.seedshiftradio.station.StationEntity;
import com.seedshiftradio.station.VoiceProfileEntity;

public record ScriptGenerationContext(
		PlayoutSessionEntity session,
		QueueItemEntity item,
		StationEntity station,
		PersonalityEntity personality,
		VoiceProfileEntity voiceProfile,
		LetterEntity letter,
		String prompt) {
}
