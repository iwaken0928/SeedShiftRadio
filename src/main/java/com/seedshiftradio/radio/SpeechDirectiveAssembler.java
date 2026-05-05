package com.seedshiftradio.radio;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.station.PersonalityEntity;
import com.seedshiftradio.station.PersonalityRepository;
import com.seedshiftradio.station.StationEntity;
import com.seedshiftradio.station.StationRepository;
import com.seedshiftradio.station.VoiceProfileEntity;
import com.seedshiftradio.station.VoiceProfileRepository;

@Service
public class SpeechDirectiveAssembler {

	private final StationRepository stationRepository;
	private final PersonalityRepository personalityRepository;
	private final VoiceProfileRepository voiceProfileRepository;
	private final ClientCapabilitiesService clientCapabilitiesService;

	public SpeechDirectiveAssembler(
			StationRepository stationRepository,
			PersonalityRepository personalityRepository,
			VoiceProfileRepository voiceProfileRepository,
			ClientCapabilitiesService clientCapabilitiesService) {
		this.stationRepository = stationRepository;
		this.personalityRepository = personalityRepository;
		this.voiceProfileRepository = voiceProfileRepository;
		this.clientCapabilitiesService = clientCapabilitiesService;
	}

	public SpeechDirectiveResponse assemble(PlayoutSessionEntity session, QueueItemEntity item, String clientId) {
		StationEntity station = stationRepository.findById(session.getStationId()).orElse(null);
		PersonalityEntity personality = resolvePersonality(station);
		VoiceProfileEntity voiceProfile = resolveVoiceProfile(station);
		ClientCapabilitiesRecord capabilities = clientId == null || clientId.isBlank()
				? null
				: clientCapabilitiesService.latest(clientId);
		String text = buildText(station, personality, item);
		return new SpeechDirectiveResponse(
				item.getSpeechDirectiveId() == null ? "sd-" + item.getId() : item.getSpeechDirectiveId(),
				text,
				normalize(text),
				List.of(),
				resolveEmotion(personality),
				resolveTempo(personality, item),
				resolvePauseHints(item),
				personality != null ? personality.getId() : station != null ? station.getLanguagePersonaId() : null,
				resolveVoiceHint(voiceProfile, capabilities),
				session.getCorrelationId());
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

	private String buildText(StationEntity station, PersonalityEntity personality, QueueItemEntity item) {
		String stationName = station != null ? station.getName() : "この番組";
		String hostName = personality != null ? personality.getDisplayName() : stationName;
		String title = item.getTitle() == null || item.getTitle().isBlank() ? item.getSegmentType().name() : item.getTitle();
		SlotRole slotRole = item.getSlotRole();
		if (slotRole == null) {
			return title + "です。";
		}
		return switch (slotRole) {
			case OPENING -> "%s、%sがお送りします。%sです。".formatted(stationName, hostName, title);
			case LETTER -> "%sでは、レターを紹介します。%sです。".formatted(stationName, title);
			case MUSIC_BREAK -> "ここで音楽をお送りします。%sです。".formatted(title);
			case ENDING -> "%sはこのあたりで一区切りです。%sです。".formatted(stationName, title);
			case TOPIC -> "%sの時間です。%sをお届けします。".formatted(title, stationName);
		};
	}

	private String normalize(String text) {
		return text == null ? null : text.replaceAll("\\s+", " ").trim();
	}

	private String resolveEmotion(PersonalityEntity personality) {
		String tone = personality == null || personality.getLanguageTone() == null
				? "calm"
				: personality.getLanguageTone().toLowerCase(Locale.ROOT);
		if (tone.contains("bright") || tone.contains("cheer") || tone.contains("happy")) {
			return "bright";
		}
		if (tone.contains("energetic") || tone.contains("lively")) {
			return "lively";
		}
		return "calm";
	}

	private String resolveTempo(PersonalityEntity personality, QueueItemEntity item) {
		if (item.getSegmentType() == SegmentType.MUSIC_AI || item.getSegmentType() == SegmentType.MUSIC_LOCAL) {
			return "slow";
		}
		String tone = personality == null || personality.getLanguageTone() == null
				? ""
				: personality.getLanguageTone().toLowerCase(Locale.ROOT);
		if (tone.contains("energetic") || tone.contains("lively")) {
			return "fast";
		}
		return "medium";
	}

	private List<PauseHint> resolvePauseHints(QueueItemEntity item) {
		if (item.getSlotRole() == SlotRole.OPENING || item.getSlotRole() == SlotRole.ENDING) {
			return List.of(new PauseHint(8, 200));
		}
		return List.of();
	}

	private String resolveVoiceHint(VoiceProfileEntity voiceProfile, ClientCapabilitiesRecord capabilities) {
		if (capabilities != null
				&& capabilities.supportsClientSideTts()
				&& capabilities.preferredPlaybackMode() == PlaybackMode.CLIENT_TTS
				&& capabilities.localVoiceProfiles() != null
				&& !capabilities.localVoiceProfiles().isEmpty()) {
			ClientCapabilitiesRequest.LocalVoiceProfile localVoiceProfile = capabilities.localVoiceProfiles().getFirst();
			return localVoiceProfile.engine() + ":" + localVoiceProfile.profileKey();
		}
		if (voiceProfile == null) {
			return null;
		}
		String base = voiceProfile.getEngineType() + ":" + voiceProfile.getSpeakerKey();
		return voiceProfile.getStyleKey() == null || voiceProfile.getStyleKey().isBlank()
				? base
				: base + ":" + voiceProfile.getStyleKey();
	}
}
