package com.seedshiftradio.programming;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.ConstraintMode;
import com.seedshiftradio.domain.LetterStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.letter.LetterRepository;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport.CompositionProfile;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport.PreGenerationProfile;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport.ReplayProfile;
import com.seedshiftradio.station.PersonalityRepository;
import com.seedshiftradio.station.StationDtos.PreviewProgram;
import com.seedshiftradio.station.StationDtos.PreviewSlot;
import com.seedshiftradio.station.StationDtos.ProgramTemplateDetailResponse;
import com.seedshiftradio.station.StationDtos.ProgramTemplateSlotRequest;
import com.seedshiftradio.station.StationDtos.ProgramTemplateSlotResponse;
import com.seedshiftradio.station.StationDtos.ProgramTemplateSummaryResponse;
import com.seedshiftradio.station.StationDtos.ProgramTemplateUpsertRequest;
import com.seedshiftradio.station.StationDtos.ProgrammingPolicyResponse;
import com.seedshiftradio.station.StationDtos.ProgrammingPolicyUpdateRequest;
import com.seedshiftradio.station.StationDtos.ProgrammingPolicyUpdateResponse;
import com.seedshiftradio.station.StationDtos.ProgrammingPreviewRequest;
import com.seedshiftradio.station.StationDtos.ProgrammingPreviewResponse;
import com.seedshiftradio.station.StationDtos.ProgrammingRuleResponse;
import com.seedshiftradio.station.StationRepository;
import com.seedshiftradio.station.VoiceProfileRepository;
import com.seedshiftradio.settings.ProviderHealthService;

@Service
public class ProgrammingService {

	private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
	private static final EnumSet<LetterStatus> PENDING_LETTER_STATUSES = EnumSet.of(LetterStatus.UNREAD, LetterStatus.PENDING, LetterStatus.ADOPTED);

	private final StationRepository stationRepository;
	private final PersonalityRepository personalityRepository;
	private final VoiceProfileRepository voiceProfileRepository;
	private final StationProgrammingPolicyRepository policyRepository;
	private final ProgramRuleRepository ruleRepository;
	private final ProgramTemplateRepository templateRepository;
	private final ProgramTemplateSlotRepository slotRepository;
	private final LetterRepository letterRepository;
	private final ProviderHealthService providerHealthService;

	public ProgrammingService(
			StationRepository stationRepository,
			PersonalityRepository personalityRepository,
			VoiceProfileRepository voiceProfileRepository,
			StationProgrammingPolicyRepository policyRepository,
			ProgramRuleRepository ruleRepository,
			ProgramTemplateRepository templateRepository,
			ProgramTemplateSlotRepository slotRepository,
			LetterRepository letterRepository,
			ProviderHealthService providerHealthService) {
		this.stationRepository = stationRepository;
		this.personalityRepository = personalityRepository;
		this.voiceProfileRepository = voiceProfileRepository;
		this.policyRepository = policyRepository;
		this.ruleRepository = ruleRepository;
		this.templateRepository = templateRepository;
		this.slotRepository = slotRepository;
		this.letterRepository = letterRepository;
		this.providerHealthService = providerHealthService;
	}

	@Transactional(readOnly = true)
	public ProgrammingPolicyResponse getPolicy(String stationId) {
		var station = stationRepository.findById(stationId)
				.orElseThrow(() -> notFound("stationId", stationId));
		var policy = policyRepository.findByStationId(stationId)
				.orElseGet(() -> defaultPolicy(stationId, station.getDefaultProgramTemplateId()));
		var rules = ruleRepository.findByPolicyIdOrderByPriorityDesc(policy.getId()).stream()
				.map(this::toRuleResponse)
				.toList();
		return new ProgrammingPolicyResponse(
				stationId,
				policy.getVersion(),
				station.isProgrammingEnabled(),
				policy.getDefaultTemplateId(),
				policy.getFallbackStrategy(),
				policy.getPlanningHorizonMinutes(),
				ProgrammingPolicyProfileSupport.toPreGenerationProfile(policy.getPreGenerationPolicy()),
				ProgrammingPolicyProfileSupport.toReplayProfile(policy.getReplayPolicy()),
				ProgrammingPolicyProfileSupport.toCompositionProfile(policy.getCompositionPolicy()),
				policy.getUpdatedAt(),
				rules);
	}

	@Transactional
	public ProgrammingPolicyUpdateResponse updatePolicy(String stationId, ProgrammingPolicyUpdateRequest request) {
		var station = stationRepository.findById(stationId)
				.orElseThrow(() -> notFound("stationId", stationId));
		validateStationReferences(station.getLanguagePersonaId(), station.getDefaultVoiceProfileId());
		validatePolicyRequest(stationId, request);

		StationProgrammingPolicyEntity policy = policyRepository.findByStationId(stationId)
				.orElseGet(() -> {
					var created = new StationProgrammingPolicyEntity();
					created.setId("policy-" + stationId);
					created.setStationId(stationId);
					return created;
				});
		if (policy.getVersion() != null && !Objects.equals(policy.getVersion(), request.version())) {
			throw conflict("version", "番組編成設定が他で更新されています。");
		}
		PreGenerationProfile preGeneration = request.preGeneration() != null
				? ProgrammingPolicyProfileSupport.materializePreGenerationProfile(request.preGeneration())
				: ProgrammingPolicyProfileSupport.toPreGenerationProfile(policy.getPreGenerationPolicy());
		ReplayProfile replay = request.replay() != null
				? ProgrammingPolicyProfileSupport.materializeReplayProfile(request.replay())
				: ProgrammingPolicyProfileSupport.toReplayProfile(policy.getReplayPolicy());
		CompositionProfile composition = request.composition() != null
				? ProgrammingPolicyProfileSupport.materializeCompositionProfile(request.composition())
				: ProgrammingPolicyProfileSupport.toCompositionProfile(policy.getCompositionPolicy());
		ProgrammingPolicyProfileSupport.validateProfiles(preGeneration, replay, composition);
		policy.setDefaultTemplateId(request.defaultTemplateId());
		policy.setFallbackStrategy(request.fallbackStrategy());
		policy.setPlanningHorizonMinutes(request.planningHorizonMinutes());
		policy.setPreGenerationPolicy(ProgrammingPolicyProfileSupport.toMap(preGeneration));
		policy.setReplayPolicy(ProgrammingPolicyProfileSupport.toMap(replay));
		policy.setCompositionPolicy(ProgrammingPolicyProfileSupport.toMap(composition));
		StationProgrammingPolicyEntity savedPolicy = policyRepository.save(policy);

		ruleRepository.deleteByPolicyId(savedPolicy.getId());
		List<ProgramRuleEntity> rules = new ArrayList<>();
		for (var ruleRequest : request.rules()) {
			ProgramRuleEntity entity = new ProgramRuleEntity();
			entity.setId(nextId("rule"));
			entity.setPolicyId(savedPolicy.getId());
			entity.setPriority(ruleRequest.priority());
			entity.setDaysOfWeek(String.join(",", ruleRequest.days()));
			entity.setStartTime(ruleRequest.startTime());
			entity.setEndTime(ruleRequest.endTime());
			entity.setMinimumPendingLetters(ruleRequest.minimumPendingLetters());
			entity.setRequiredProviderStates(ruleRequest.requiredProviderStates() == null ? List.of() : List.copyOf(ruleRequest.requiredProviderStates()));
			entity.setTemplateId(ruleRequest.templateId());
			rules.add(entity);
		}
		ruleRepository.saveAll(rules);

		station.setProgrammingEnabled(request.enabled());
		station.setDefaultProgramTemplateId(request.defaultTemplateId());
		stationRepository.save(station);

		return new ProgrammingPolicyUpdateResponse(
				stationId,
				savedPolicy.getVersion(),
				request.enabled(),
				preGeneration,
				replay,
				composition,
				savedPolicy.getUpdatedAt());
	}

	@Transactional(readOnly = true)
	public ProgrammingPreviewResponse preview(String stationId, ProgrammingPreviewRequest request) {
		ResolvedProgramPlan plan = resolvePlan(
				stationId,
				request.at(),
				request.pendingLetterCount(),
				toProviderStateMap(request.providerStates()));
		return toPreview(stationId, plan);
	}

	@Transactional(readOnly = true)
	public ResolvedProgramPlan resolveCurrentPlan(String stationId, OffsetDateTime at) {
		long pendingLetters = letterRepository.countByStationIdAndStatusIn(stationId, PENDING_LETTER_STATUSES);
		return resolvePlan(stationId, at, Math.toIntExact(pendingLetters), currentProviderStates());
	}

	@Transactional(readOnly = true)
	public List<ProgramTemplateSummaryResponse> listTemplates() {
		return templateRepository.findAllByOrderByNameAsc().stream()
				.map(template -> new ProgramTemplateSummaryResponse(
						template.getId(),
						template.getScope(),
						template.getStationId(),
						template.getName(),
						template.getVersion(),
						template.getTargetDurationMinutes(),
						template.getPlanningHorizonMinutes(),
						template.isActive(),
						template.getFallbackTemplateId()))
				.toList();
	}

	@Transactional(readOnly = true)
	public ProgramTemplateDetailResponse getTemplate(String templateId) {
		ProgramTemplateEntity template = templateRepository.findById(templateId)
				.orElseThrow(() -> notFound("templateId", templateId));
		return toTemplateDetail(template);
	}

	@Transactional
	public ProgramTemplateDetailResponse createTemplate(ProgramTemplateUpsertRequest request) {
		if (templateRepository.existsById(request.id())) {
			throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "同じテンプレート ID が既に存在します。", Map.of("templateId", request.id()));
		}
		ProgramTemplateEntity template = new ProgramTemplateEntity();
		template.setId(request.id());
		template.setVersion(request.version() == null ? 1 : request.version());
		return saveTemplate(template, request);
	}

	@Transactional
	public ProgramTemplateDetailResponse updateTemplate(String templateId, ProgramTemplateUpsertRequest request) {
		ProgramTemplateEntity template = templateRepository.findById(templateId)
				.orElseThrow(() -> notFound("templateId", templateId));
		if (request.version() != null && !Objects.equals(request.version(), template.getVersion())) {
			throw conflict("version", "番組テンプレートが他で更新されています。");
		}
		template.setVersion(template.getVersion() + 1);
		return saveTemplate(template, request);
	}

	private ProgramTemplateDetailResponse saveTemplate(ProgramTemplateEntity template, ProgramTemplateUpsertRequest request) {
		validateTemplateRequest(request, template.getId());
		template.setScope(request.scope());
		template.setStationId(request.stationId());
		template.setName(request.name());
		template.setTargetDurationMinutes(request.targetDurationMinutes());
		template.setPlanningHorizonMinutes(request.planningHorizonMinutes());
		template.setActive(request.isActive());
		template.setEditorialPolicy(request.editorialPolicy() == null ? Map.of() : new LinkedHashMap<>(request.editorialPolicy()));
		template.setFallbackTemplateId(request.fallbackTemplateId());
		ProgramTemplateEntity savedTemplate = templateRepository.save(template);

		slotRepository.deleteByProgramTemplateId(savedTemplate.getId());
		List<ProgramTemplateSlotEntity> slots = request.slots().stream()
				.map(slot -> toSlotEntity(savedTemplate.getId(), slot))
				.toList();
		slotRepository.saveAll(slots);
		return toTemplateDetail(savedTemplate);
	}

	private ResolvedProgramPlan resolvePlan(
			String stationId,
			OffsetDateTime at,
			int pendingLetterCount,
			Map<String, String> providerStates) {
		var station = stationRepository.findById(stationId)
				.orElseThrow(() -> notFound("stationId", stationId));
		var policy = policyRepository.findByStationId(stationId)
				.orElseGet(() -> defaultPolicy(stationId, station.getDefaultProgramTemplateId()));
		List<String> warnings = new ArrayList<>();
		ProgramRuleEntity selectedRule = selectRule(policy.getId(), at, pendingLetterCount, providerStates);
		ProgramTemplateEntity template = null;
		String selectedTemplateId = null;
		boolean fallbackApplied = false;
		if (selectedRule != null) {
			template = templateRepository.findById(selectedRule.getTemplateId()).orElse(null);
			if (template != null && template.isActive()) {
				selectedTemplateId = template.getId();
			}
		}
		if (template == null || !template.isActive()) {
			if (policy.getDefaultTemplateId() != null) {
				ProgramTemplateEntity defaultTemplate = templateRepository.findById(policy.getDefaultTemplateId()).orElse(null);
				if (defaultTemplate != null && defaultTemplate.isActive()) {
					template = defaultTemplate;
					selectedTemplateId = defaultTemplate.getId();
				}
			}
		}
		if (template == null || !template.isActive()) {
			warnings.add("有効なテンプレートが見つからないため固定比率へ縮退しました。");
			return legacyFallbackPlan(warnings, true);
		}
		ResolvedSlotResolution resolvedSlots = resolveSlots(template, providerStates, pendingLetterCount, warnings);
		return new ResolvedProgramPlan(
				selectedTemplateId,
				template.getVersion(),
				template.getName(),
				template.getTargetDurationMinutes() * 60_000,
				resolvedSlots.slots(),
				fallbackApplied || resolvedSlots.fallbackApplied(),
				warnings);
	}

	private ProgramRuleEntity selectRule(String policyId, OffsetDateTime at, int pendingLetterCount, Map<String, String> providerStates) {
		for (ProgramRuleEntity rule : ruleRepository.findByPolicyIdOrderByPriorityDesc(policyId)) {
			if (!matchesDay(rule.getDaysOfWeek(), at.getDayOfWeek())) {
				continue;
			}
			if (!matchesTimeWindow(rule.getStartTime(), rule.getEndTime(), at.toLocalTime())) {
				continue;
			}
			if (pendingLetterCount < rule.getMinimumPendingLetters()) {
				continue;
			}
			if (!requiredStatesSatisfied(rule.getRequiredProviderStates(), providerStates)) {
				continue;
			}
			return rule;
		}
		return null;
	}

	private ResolvedSlotResolution resolveSlots(
			ProgramTemplateEntity template,
			Map<String, String> providerStates,
			int pendingLetterCount,
			List<String> warnings) {
		List<ResolvedSlot> resolvedSlots = new ArrayList<>();
		boolean fallbackApplied = false;
		for (ProgramTemplateSlotEntity slot : slotRepository.findByProgramTemplateIdOrderBySequenceNoAsc(template.getId())) {
			SegmentType resolved = resolveSegmentType(slot, providerStates, pendingLetterCount);
			boolean usedFallback = false;
			if (resolved == null) {
				resolved = resolveFallbackSegmentType(slot, providerStates, pendingLetterCount);
				usedFallback = resolved != null;
			}
			if (resolved == null) {
				warnings.add("slot " + slot.getId() + " は解決不能のため TALK へ縮退しました。");
				resolved = SegmentType.TALK;
				usedFallback = true;
			}
			if (usedFallback) {
				fallbackApplied = true;
				warnings.add("slot " + slot.getId() + " は fallback を適用しました。");
			}
			resolvedSlots.add(new ResolvedSlot(slot.getId(), slot.getRole(), slot.getConstraintMode(), slot.getTargetDurationMs(), resolved));
		}
		return new ResolvedSlotResolution(List.copyOf(resolvedSlots), fallbackApplied);
	}

	private SegmentType resolveSegmentType(ProgramTemplateSlotEntity slot, Map<String, String> providerStates, int pendingLetterCount) {
		for (String candidate : slot.getCandidateSegmentTypes()) {
			SegmentType type = ProgrammingSupport.parseSegmentTypeOrThrow(
					candidate,
					"candidateSegmentTypes",
					slot.getId(),
					HttpStatus.INTERNAL_SERVER_ERROR,
					"INVALID_TEMPLATE");
			if (isSegmentTypeAvailable(type, providerStates, pendingLetterCount)) {
				return type;
			}
		}
		return null;
	}

	private SegmentType resolveFallbackSegmentType(ProgramTemplateSlotEntity slot, Map<String, String> providerStates, int pendingLetterCount) {
		for (String candidate : slot.getFallbackSegmentTypes()) {
			SegmentType type = ProgrammingSupport.parseSegmentTypeOrThrow(
					candidate,
					"fallbackSegmentTypes",
					slot.getId(),
					HttpStatus.INTERNAL_SERVER_ERROR,
					"INVALID_TEMPLATE");
			if (isSegmentTypeAvailable(type, providerStates, pendingLetterCount)) {
				return type;
			}
		}
		return null;
	}

	private boolean isSegmentTypeAvailable(SegmentType type, Map<String, String> providerStates, int pendingLetterCount) {
		return switch (type) {
			case LETTER -> pendingLetterCount > 0;
			case MUSIC_AI -> "UP".equalsIgnoreCase(providerStates.getOrDefault("musicGen", "UNKNOWN"));
			case TALK -> "UP".equalsIgnoreCase(providerStates.getOrDefault("tts", "UP"))
					|| "UP".equalsIgnoreCase(providerStates.getOrDefault("llm", "UP"));
			default -> true;
		};
	}

	private Map<String, String> currentProviderStates() {
		Map<String, com.seedshiftradio.settings.SettingsDtos.ProviderHealthPayload> health = providerHealthService.getLatestOrProbe();
		return Map.of(
				"musicGen", providerStatus(health, "musicGen"),
				"tts", providerStatus(health, "tts"),
				"llm", providerStatus(health, "llm"));
	}

	private String providerStatus(Map<String, com.seedshiftradio.settings.SettingsDtos.ProviderHealthPayload> health, String key) {
		com.seedshiftradio.settings.SettingsDtos.ProviderHealthPayload payload = health.get(key);
		return payload == null || payload.status() == null ? "UNKNOWN" : payload.status();
	}

	private ResolvedProgramPlan legacyFallbackPlan(List<String> warnings, boolean fallbackApplied) {
		List<ResolvedSlot> slots = List.of(
				new ResolvedSlot("legacy-talk", SlotRole.OPENING, ConstraintMode.SOFT, 60_000, SegmentType.TALK),
				new ResolvedSlot("legacy-music", SlotRole.MUSIC_BREAK, ConstraintMode.SOFT, 90_000, SegmentType.MUSIC_LOCAL),
				new ResolvedSlot("legacy-letter", SlotRole.LETTER, ConstraintMode.SOFT, 60_000, SegmentType.LETTER),
				new ResolvedSlot("legacy-jingle", SlotRole.ENDING, ConstraintMode.SOFT, 15_000, SegmentType.JINGLE));
		if (warnings.isEmpty()) {
			warnings.add("固定比率 fallback を使用しました。");
		}
		return new ResolvedProgramPlan(null, null, "Legacy Ratio Fallback", 225_000, slots, fallbackApplied, warnings);
	}

	private ProgrammingPreviewResponse toPreview(String stationId, ResolvedProgramPlan plan) {
		return new ProgrammingPreviewResponse(
				stationId,
				plan.templateId(),
				plan.fallbackApplied(),
				new PreviewProgram(plan.title(), plan.plannedDurationMs()),
				plan.slots().stream()
						.map(slot -> new PreviewSlot(
								slot.slotId(),
								slot.role(),
								slot.constraintMode(),
								slot.targetDurationMs(),
								slot.resolvedSegmentType().name()))
						.toList(),
				plan.validationWarnings());
	}

	private ProgramTemplateDetailResponse toTemplateDetail(ProgramTemplateEntity template) {
		return new ProgramTemplateDetailResponse(
				template.getId(),
				template.getScope(),
				template.getStationId(),
				template.getName(),
				template.getVersion(),
				template.getTargetDurationMinutes(),
				template.getPlanningHorizonMinutes(),
				template.isActive(),
				template.getEditorialPolicy(),
				template.getFallbackTemplateId(),
				slotRepository.findByProgramTemplateIdOrderBySequenceNoAsc(template.getId()).stream()
						.map(slot -> new ProgramTemplateSlotResponse(
								slot.getId(),
								slot.getSequenceNo(),
								slot.getRole(),
								slot.getConstraintMode(),
								slot.getCandidateSegmentTypes(),
								slot.getFallbackSegmentTypes(),
								slot.getTargetDurationMs(),
								slot.getSlotPolicy()))
						.toList());
	}

	private ProgramTemplateSlotEntity toSlotEntity(String templateId, ProgramTemplateSlotRequest request) {
		ProgramTemplateSlotEntity entity = new ProgramTemplateSlotEntity();
		entity.setId(request.id() == null || request.id().isBlank() ? nextId("slot") : request.id());
		entity.setProgramTemplateId(templateId);
		entity.setSequenceNo(request.sequenceNo());
		entity.setRole(request.role());
		entity.setConstraintMode(request.constraintMode());
		entity.setCandidateSegmentTypes(List.copyOf(request.candidateSegmentTypes()));
		entity.setFallbackSegmentTypes(request.fallbackSegmentTypes() == null ? List.of() : List.copyOf(request.fallbackSegmentTypes()));
		entity.setTargetDurationMs(request.targetDurationMs());
		entity.setSlotPolicy(request.slotPolicy() == null ? Map.of() : new LinkedHashMap<>(request.slotPolicy()));
		return entity;
	}

	private void validateStationReferences(String personalityId, String voiceProfileId) {
		if (!personalityRepository.existsById(personalityId)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "既定人格が存在しません。", Map.of("languagePersonaId", personalityId));
		}
		if (!voiceProfileRepository.existsById(voiceProfileId)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "既定音声が存在しません。", Map.of("defaultVoiceProfileId", voiceProfileId));
		}
	}

	private void validatePolicyRequest(String stationId, ProgrammingPolicyUpdateRequest request) {
		if (request.enabled() && (request.rules() == null || request.rules().isEmpty())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "自動番組生成を有効にする場合は 1 件以上の rule が必要です。", Map.of("stationId", stationId));
		}
		if (request.defaultTemplateId() != null) {
			validateTemplateScope(stationId, request.defaultTemplateId());
		}
		if (request.rules() != null) {
			for (var rule : request.rules()) {
				validateTemplateScope(stationId, rule.templateId());
			}
		}
	}

	private void validateTemplateRequest(ProgramTemplateUpsertRequest request, String currentTemplateId) {
		if ("STATION".equalsIgnoreCase(request.scope())) {
			if (request.stationId() == null || !stationRepository.existsById(request.stationId())) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "STATION scope には有効な stationId が必要です。", Map.of("stationId", request.stationId()));
			}
		}
		if ("GLOBAL".equalsIgnoreCase(request.scope()) && request.stationId() != null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "GLOBAL scope に stationId は設定できません。", Map.of("stationId", request.stationId()));
		}
		if (request.fallbackTemplateId() != null) {
			if (request.fallbackTemplateId().equals(currentTemplateId)) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE", "fallbackTemplateId に自分自身は指定できません。", Map.of("templateId", currentTemplateId));
			}
			ProgramTemplateEntity fallback = templateRepository.findById(request.fallbackTemplateId())
					.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE", "fallbackTemplateId が存在しません。", Map.of("fallbackTemplateId", request.fallbackTemplateId())));
			if (fallback.getFallbackTemplateId() != null && fallback.getFallbackTemplateId().equals(currentTemplateId)) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE", "fallbackTemplateId が循環しています。", Map.of("fallbackTemplateId", request.fallbackTemplateId()));
			}
		}
		for (ProgramTemplateSlotRequest slot : request.slots()) {
			ProgrammingSupport.validateSegmentTypes(slot.candidateSegmentTypes(), "candidateSegmentTypes", slot.id());
			ProgrammingSupport.validateSegmentTypes(slot.fallbackSegmentTypes(), "fallbackSegmentTypes", slot.id());
		}
	}

	private void validateTemplateScope(String stationId, String templateId) {
		ProgramTemplateEntity template = templateRepository.findById(templateId)
				.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE", "指定されたテンプレートが存在しません。", Map.of("templateId", templateId)));
		boolean global = "GLOBAL".equalsIgnoreCase(template.getScope());
		boolean sameStation = stationId.equals(template.getStationId());
		if (!global && !sameStation) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE", "テンプレートは同一局または GLOBAL scope である必要があります。", Map.of("templateId", templateId));
		}
	}

	private boolean matchesDay(String dayCsv, DayOfWeek dayOfWeek) {
		String shortDay = dayOfWeek.name().substring(0, 3);
		Set<String> daySet = Set.of(dayCsv.split(","));
		return daySet.contains(shortDay);
	}

	private boolean matchesTimeWindow(String startTime, String endTime, LocalTime current) {
		LocalTime start = LocalTime.parse(startTime, TIME_FORMATTER);
		LocalTime end = LocalTime.parse(endTime, TIME_FORMATTER);
		if (end.isAfter(start) || end.equals(start)) {
			return !current.isBefore(start) && current.isBefore(end);
		}
		return !current.isBefore(start) || current.isBefore(end);
	}

	private boolean requiredStatesSatisfied(List<String> requiredStates, Map<String, String> providerStates) {
		for (String requiredState : requiredStates) {
			String normalized = requiredState.toUpperCase(Locale.ROOT);
			if (normalized.equals("MUSICGEN_UP") && !"UP".equalsIgnoreCase(providerStates.getOrDefault("musicGen", "UNKNOWN"))) {
				return false;
			}
			if (normalized.equals("TTS_UP") && !"UP".equalsIgnoreCase(providerStates.getOrDefault("tts", "UNKNOWN"))) {
				return false;
			}
			if (normalized.equals("LLM_UP") && !"UP".equalsIgnoreCase(providerStates.getOrDefault("llm", "UNKNOWN"))) {
				return false;
			}
		}
		return true;
	}

	private Map<String, String> toProviderStateMap(com.seedshiftradio.station.StationDtos.ProviderStatesRequest providerStates) {
		if (providerStates == null) {
			return Map.of("musicGen", "UNKNOWN", "tts", "UNKNOWN", "llm", "UNKNOWN");
		}
		return Map.of(
				"musicGen", providerStates.musicGen() == null ? "UNKNOWN" : providerStates.musicGen(),
				"tts", providerStates.tts() == null ? "UNKNOWN" : providerStates.tts(),
				"llm", providerStates.llm() == null ? "UNKNOWN" : providerStates.llm());
	}

	private ProgrammingRuleResponse toRuleResponse(ProgramRuleEntity rule) {
		return new ProgrammingRuleResponse(
				rule.getId(),
				rule.getPriority(),
				List.of(rule.getDaysOfWeek().split(",")),
				rule.getStartTime(),
				rule.getEndTime(),
				rule.getMinimumPendingLetters(),
				rule.getRequiredProviderStates(),
				rule.getTemplateId());
	}

	private StationProgrammingPolicyEntity defaultPolicy(String stationId, String defaultTemplateId) {
		StationProgrammingPolicyEntity policy = new StationProgrammingPolicyEntity();
		policy.setId("policy-" + stationId);
		policy.setStationId(stationId);
		policy.setVersion(0);
		policy.setDefaultTemplateId(defaultTemplateId);
		policy.setFallbackStrategy("LEGACY_RATIO");
		policy.setPlanningHorizonMinutes(20);
		policy.setPreGenerationPolicy(ProgrammingPolicyProfileSupport.toMap(ProgrammingPolicyProfileSupport.defaultPreGenerationProfile()));
		policy.setReplayPolicy(ProgrammingPolicyProfileSupport.toMap(ProgrammingPolicyProfileSupport.defaultReplayProfile()));
		policy.setCompositionPolicy(ProgrammingPolicyProfileSupport.toMap(ProgrammingPolicyProfileSupport.defaultCompositionProfile()));
		policy.setUpdatedAt(Instant.now());
		return policy;
	}

	private ApiException notFound(String key, String value) {
		return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "対象が見つかりません。", Map.of(key, value));
	}

	private ApiException conflict(String key, String message) {
		return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message, Map.of("field", key));
	}

	private String nextId(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	public record ResolvedProgramPlan(
			String templateId,
			Integer templateVersion,
			String title,
			Integer plannedDurationMs,
			List<ResolvedSlot> slots,
			boolean fallbackApplied,
			List<String> validationWarnings) {
	}

	public record ResolvedSlot(
			String slotId,
			SlotRole role,
			ConstraintMode constraintMode,
			Integer targetDurationMs,
			SegmentType resolvedSegmentType) {
	}

	private record ResolvedSlotResolution(
			List<ResolvedSlot> slots,
			boolean fallbackApplied) {
	}
}
