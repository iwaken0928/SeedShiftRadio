package com.seedshiftradio.station;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.programming.ProgramTemplateEntity;
import com.seedshiftradio.programming.ProgramTemplateRepository;
import com.seedshiftradio.programming.ProgrammingDtos;
import com.seedshiftradio.programming.StationProgrammingPolicyEntity;
import com.seedshiftradio.programming.StationProgrammingPolicyRepository;

@Service
@Transactional
public class StationAdminService {

	private final StationRepository stationRepository;
	private final PersonalityRepository personalityRepository;
	private final VoiceProfileRepository voiceProfileRepository;
	private final ProgramTemplateRepository programTemplateRepository;
	private final StationProgrammingPolicyRepository policyRepository;

	public StationAdminService(
			StationRepository stationRepository,
			PersonalityRepository personalityRepository,
			VoiceProfileRepository voiceProfileRepository,
			ProgramTemplateRepository programTemplateRepository,
			StationProgrammingPolicyRepository policyRepository) {
		this.stationRepository = stationRepository;
		this.personalityRepository = personalityRepository;
		this.voiceProfileRepository = voiceProfileRepository;
		this.programTemplateRepository = programTemplateRepository;
		this.policyRepository = policyRepository;
	}

	@Transactional(readOnly = true)
	public List<StationDtos.StationSummary> listStations() {
		return stationRepository.findAll().stream().map(this::toSummary).toList();
	}

	@Transactional(readOnly = true)
	public StationDtos.StationDetail getStation(String id) {
		return toDetail(findStation(id));
	}

	public StationDtos.StationResponse createStation(StationDtos.StationUpsertRequest request) {
		if (stationRepository.existsById(request.id())) {
			throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "同じ局IDが既に存在します。", java.util.Map.of("id", request.id()));
		}
		validateFrequencyAvailability(request.frequencyMHz(), request.id());
		validateStationReferences(request.languagePersonaId(), request.defaultVoiceProfileId(), request.defaultProgramTemplateId(), request.id());
		StationEntity entity = new StationEntity(
				request.id(),
				request.name(),
				request.frequencyMHz(),
				request.genre(),
				request.languagePersonaId(),
				request.defaultVoiceProfileId(),
				request.programmingEnabled(),
				request.defaultProgramTemplateId(),
				request.isActive());
		StationEntity saved = saveStation(entity);
		return toResponse(saved);
	}

	public StationDtos.StationResponse updateStation(String id, StationDtos.StationUpsertRequest request) {
		StationEntity existing = findStation(id);
		if (!id.equals(request.id())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "パスの局IDとリクエストの局IDが一致しません。", java.util.Map.of("id", id));
		}
		if (request.version() == null || !request.version().equals(existing.getVersion())) {
			throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "局の version が競合しています。", java.util.Map.of("expectedVersion", existing.getVersion(), "providedVersion", request.version()));
		}
		validateFrequencyAvailability(request.frequencyMHz(), id);
		validateStationReferences(request.languagePersonaId(), request.defaultVoiceProfileId(), request.defaultProgramTemplateId(), id);
		existing.setName(request.name());
		existing.setFrequencyMhz(request.frequencyMHz());
		existing.setGenre(request.genre());
		existing.setLanguagePersonaId(request.languagePersonaId());
		existing.setDefaultVoiceProfileId(request.defaultVoiceProfileId());
		existing.setProgrammingEnabled(request.programmingEnabled());
		existing.setDefaultProgramTemplateId(request.defaultProgramTemplateId());
		existing.setActive(request.isActive());
		return toResponse(saveStation(existing));
	}

	private StationEntity findStation(String id) {
		return stationRepository.findById(id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "局が見つかりません。", java.util.Map.of("id", id)));
	}

	private void validateStationReferences(String personaId, String voiceProfileId, String templateId, String stationId) {
		if (!personalityRepository.existsById(personaId)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "指定の人格が存在しません。", java.util.Map.of("languagePersonaId", personaId));
		}
		if (!voiceProfileRepository.existsById(voiceProfileId)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "指定の音声プロファイルが存在しません。", java.util.Map.of("defaultVoiceProfileId", voiceProfileId));
		}
		if (templateId != null) {
			ProgramTemplateEntity template = programTemplateRepository.findById(templateId)
					.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE", "指定の番組テンプレートが存在しません。", java.util.Map.of("defaultProgramTemplateId", templateId)));
			validateTemplateScope(template, stationId);
		}
	}

	private void validateTemplateScope(ProgramTemplateEntity template, String stationId) {
		if ("GLOBAL".equalsIgnoreCase(template.getScope())) {
			return;
		}
		if (!"STATION".equalsIgnoreCase(template.getScope()) || template.getStationId() == null || !template.getStationId().equals(stationId)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE", "番組テンプレートのスコープが局に一致しません。", java.util.Map.of("templateId", template.getId()));
		}
	}

	private StationDtos.StationSummary toSummary(StationEntity entity) {
		return new StationDtos.StationSummary(
				entity.getId(),
				entity.getName(),
				entity.getFrequencyMhz(),
				entity.getGenre(),
				entity.isActive(),
				entity.isProgrammingEnabled(),
				entity.getDefaultProgramTemplateId());
	}

	private StationDtos.StationDetail toDetail(StationEntity entity) {
		StationProgrammingPolicyEntity policy = policyRepository.findByStationId(entity.getId()).orElse(null);
		StationDtos.Programming programming = new StationDtos.Programming(
				policy != null && entity.isProgrammingEnabled(),
				policy != null ? policy.getDefaultTemplateId() : entity.getDefaultProgramTemplateId(),
				policy != null ? policy.getFallbackStrategy() : null,
				policy != null ? policy.getPlanningHorizonMinutes() : null,
				policy != null ? policy.getVersion() : entity.getVersion());
		return new StationDtos.StationDetail(
				entity.getId(),
				entity.getName(),
				entity.getFrequencyMhz(),
				entity.getGenre(),
				entity.getLanguagePersonaId(),
				entity.getDefaultVoiceProfileId(),
				entity.isActive(),
				programming);
	}

	private StationDtos.StationResponse toResponse(StationEntity entity) {
		return new StationDtos.StationResponse(
				entity.getId(),
				entity.getName(),
				entity.getFrequencyMhz(),
				entity.getGenre(),
				entity.getLanguagePersonaId(),
				entity.getDefaultVoiceProfileId(),
				entity.isActive(),
				entity.isProgrammingEnabled(),
				entity.getDefaultProgramTemplateId(),
				entity.getVersion(),
				entity.getUpdatedAt());
	}

	@Transactional(readOnly = true)
	public void validateFrequencyAvailability(BigDecimal frequency, String stationId) {
		for (StationEntity station : stationRepository.findAll()) {
			if (!station.getId().equals(stationId) && station.getFrequencyMhz().compareTo(frequency) == 0) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "周波数が重複しています。", java.util.Map.of("frequencyMHz", frequency));
			}
		}
	}

	private StationEntity saveStation(StationEntity station) {
		try {
			return stationRepository.save(station);
		} catch (DataIntegrityViolationException exception) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					"周波数が重複しています。",
					java.util.Map.of("frequencyMHz", station.getFrequencyMhz()));
		}
	}
}
