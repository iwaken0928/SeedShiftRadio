package com.seedshiftradio.station;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.programming.ProgrammingService;
import com.seedshiftradio.station.StationDtos.StationDetailResponse;
import com.seedshiftradio.station.StationDtos.StationProgrammingSummary;
import com.seedshiftradio.station.StationDtos.StationSummaryResponse;
import com.seedshiftradio.station.StationDtos.StationUpsertRequest;

@Service
public class StationService {

	private final StationRepository stationRepository;
	private final PersonalityRepository personalityRepository;
	private final VoiceProfileRepository voiceProfileRepository;
	private final ProgrammingService programmingService;

	public StationService(
			StationRepository stationRepository,
			PersonalityRepository personalityRepository,
			VoiceProfileRepository voiceProfileRepository,
			ProgrammingService programmingService) {
		this.stationRepository = stationRepository;
		this.personalityRepository = personalityRepository;
		this.voiceProfileRepository = voiceProfileRepository;
		this.programmingService = programmingService;
	}

	@Transactional(readOnly = true)
	public List<StationSummaryResponse> list() {
		return stationRepository.findAll().stream()
				.map(station -> new StationSummaryResponse(
						station.getId(),
						station.getName(),
						station.getFrequencyMhz(),
						station.getGenre(),
						station.isActive(),
						station.isProgrammingEnabled(),
						station.getDefaultProgramTemplateId()))
				.toList();
	}

	@Transactional(readOnly = true)
	public StationDetailResponse get(String stationId) {
		StationEntity station = stationRepository.findById(stationId)
				.orElseThrow(() -> notFound(stationId));
		var policy = programmingService.getPolicy(stationId);
		return new StationDetailResponse(
				station.getId(),
				station.getName(),
				station.getFrequencyMhz(),
				station.getGenre(),
				station.getLanguagePersonaId(),
				station.getDefaultVoiceProfileId(),
				station.isActive(),
				station.getVersion(),
				new StationProgrammingSummary(
						policy.enabled(),
						policy.defaultTemplateId(),
						policy.fallbackStrategy(),
						policy.planningHorizonMinutes()));
	}

	@Transactional
	public StationDetailResponse create(StationUpsertRequest request) {
		if (stationRepository.existsById(request.id())) {
			throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "同じ stationId が既に存在します。", Map.of("stationId", request.id()));
		}
		validateReferences(request);
		ensureFrequencyUnique(request.id(), request.frequencyMHz());
		StationEntity station = new StationEntity(
				request.id(),
				request.name(),
				request.frequencyMHz(),
				request.genre(),
				request.languagePersonaId(),
				request.defaultVoiceProfileId(),
				request.programmingEnabled(),
				request.defaultProgramTemplateId(),
				request.isActive());
		stationRepository.save(station);
		return get(station.getId());
	}

	@Transactional
	public StationDetailResponse update(String stationId, StationUpsertRequest request) {
		StationEntity station = stationRepository.findById(stationId)
				.orElseThrow(() -> notFound(stationId));
		if (!stationId.equals(request.id())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "path の stationId と body の id が一致しません。", Map.of("stationId", stationId, "requestId", request.id()));
		}
		if (request.version() != null && !request.version().equals(station.getVersion())) {
			throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "局設定が他で更新されています。", Map.of("stationId", stationId));
		}
		validateReferences(request);
		ensureFrequencyUnique(stationId, request.frequencyMHz());
		station.setName(request.name());
		station.setFrequencyMhz(request.frequencyMHz());
		station.setGenre(request.genre());
		station.setLanguagePersonaId(request.languagePersonaId());
		station.setDefaultVoiceProfileId(request.defaultVoiceProfileId());
		station.setActive(request.isActive());
		station.setProgrammingEnabled(request.programmingEnabled());
		station.setDefaultProgramTemplateId(request.defaultProgramTemplateId());
		stationRepository.save(station);
		return get(station.getId());
	}

	private void validateReferences(StationUpsertRequest request) {
		if (!personalityRepository.existsById(request.languagePersonaId())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "既定人格が存在しません。", Map.of("languagePersonaId", request.languagePersonaId()));
		}
		if (!voiceProfileRepository.existsById(request.defaultVoiceProfileId())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "既定音声が存在しません。", Map.of("defaultVoiceProfileId", request.defaultVoiceProfileId()));
		}
	}

	private void ensureFrequencyUnique(String currentStationId, java.math.BigDecimal frequencyMHz) {
		boolean duplicated = stationRepository.findAll().stream()
				.anyMatch(existing -> !existing.getId().equals(currentStationId) && existing.getFrequencyMhz().compareTo(frequencyMHz) == 0);
		if (duplicated) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "frequencyMHz が他局と重複しています。", Map.of("frequencyMHz", frequencyMHz));
		}
	}

	private ApiException notFound(String stationId) {
		return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "指定された局が見つかりません。", Map.of("stationId", stationId));
	}
}
