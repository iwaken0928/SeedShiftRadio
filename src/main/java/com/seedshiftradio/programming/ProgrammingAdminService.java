package com.seedshiftradio.programming;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport.CompositionProfile;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport.PreGenerationProfile;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport.ReplayProfile;
import com.seedshiftradio.station.StationEntity;
import com.seedshiftradio.station.StationRepository;

@Service
@Transactional
public class ProgrammingAdminService {

	private final StationRepository stationRepository;
	private final ProgramTemplateRepository templateRepository;
	private final ProgramTemplateSlotRepository slotRepository;
	private final StationProgrammingPolicyRepository policyRepository;
	private final ProgramRuleRepository ruleRepository;

	public ProgrammingAdminService(
			StationRepository stationRepository,
			ProgramTemplateRepository templateRepository,
			ProgramTemplateSlotRepository slotRepository,
			StationProgrammingPolicyRepository policyRepository,
			ProgramRuleRepository ruleRepository) {
		this.stationRepository = stationRepository;
		this.templateRepository = templateRepository;
		this.slotRepository = slotRepository;
		this.policyRepository = policyRepository;
		this.ruleRepository = ruleRepository;
	}

	@Transactional(readOnly = true)
	public List<ProgrammingDtos.ProgramTemplateSummary> listTemplates() {
		return templateRepository.findAllByOrderByNameAsc().stream().map(this::toSummary).toList();
	}

	@Transactional(readOnly = true)
	public ProgrammingDtos.ProgramTemplateDetail getTemplate(String id) {
		return toDetail(findTemplate(id));
	}

	public ProgrammingDtos.ProgramTemplateDetail createTemplate(ProgrammingDtos.ProgramTemplateRequest request) {
		if (templateRepository.existsById(request.id())) {
			throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "同じテンプレートIDが既に存在します。", Map.of("id", request.id()));
		}
		ProgramTemplateEntity entity = buildTemplate(null, request);
		validateTemplateGraph(entity, request.slots());
		ProgramTemplateEntity saved = templateRepository.save(entity);
		saveSlots(saved.getId(), request.slots());
		return getTemplate(saved.getId());
	}

	public ProgrammingDtos.ProgramTemplateDetail updateTemplate(String id, ProgrammingDtos.ProgramTemplateRequest request) {
		ProgramTemplateEntity existing = findTemplate(id);
		if (!id.equals(request.id())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "パスのテンプレートIDとリクエストのIDが一致しません。", Map.of("id", id));
		}
		if (!request.version().equals(existing.getVersion())) {
			throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "番組テンプレートの version が競合しています。", Map.of("expectedVersion", existing.getVersion(), "providedVersion", request.version()));
		}
		ProgramTemplateEntity updated = buildTemplate(existing, request);
		updated.setVersion(request.version() + 1);
		validateTemplateGraph(updated, request.slots());
		ProgramTemplateEntity saved = templateRepository.save(updated);
		slotRepository.deleteByProgramTemplateId(saved.getId());
		saveSlots(saved.getId(), request.slots());
		return getTemplate(saved.getId());
	}

	@Transactional(readOnly = true)
	public List<ProgrammingDtos.ProgramRuleDto> getRules(String stationId) {
		StationEntity station = findStation(stationId);
		return getRules(loadPolicy(station));
	}

	public ProgrammingDtos.ProgrammingPolicyResponse savePolicy(String stationId, ProgrammingDtos.ProgrammingPolicyRequest request) {
		StationEntity station = findStation(stationId);
		StationProgrammingPolicyEntity entity = policyRepository.findByStationId(stationId).orElseGet(() -> {
			StationProgrammingPolicyEntity created = new StationProgrammingPolicyEntity();
			created.setId("policy-" + stationId);
			created.setStationId(stationId);
			return created;
		});
		if (entity.getVersion() != null && !request.version().equals(entity.getVersion())) {
			throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "番組編成ポリシーの version が競合しています。", Map.of("expectedVersion", entity.getVersion(), "providedVersion", request.version()));
		}
		if (request.enabled() && request.rules().isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "有効な番組編成には少なくとも 1 つのルールが必要です。", Map.of("stationId", stationId));
		}
		validatePolicyReferences(stationId, request.defaultTemplateId(), request.rules());
		PreGenerationProfile preGeneration = request.preGeneration() != null
				? ProgrammingPolicyProfileSupport.materializePreGenerationProfile(request.preGeneration())
				: ProgrammingPolicyProfileSupport.toPreGenerationProfile(entity.getPreGenerationPolicy());
		ReplayProfile replay = request.replay() != null
				? ProgrammingPolicyProfileSupport.materializeReplayProfile(request.replay())
				: ProgrammingPolicyProfileSupport.toReplayProfile(entity.getReplayPolicy());
		CompositionProfile composition = request.composition() != null
				? ProgrammingPolicyProfileSupport.materializeCompositionProfile(request.composition())
				: ProgrammingPolicyProfileSupport.toCompositionProfile(entity.getCompositionPolicy());
		ProgrammingPolicyProfileSupport.validateProfiles(preGeneration, replay, composition);
		entity.setDefaultTemplateId(request.defaultTemplateId());
		entity.setFallbackStrategy(request.fallbackStrategy());
		entity.setPlanningHorizonMinutes(request.planningHorizonMinutes());
		entity.setPreGenerationPolicy(ProgrammingPolicyProfileSupport.toMap(preGeneration));
		entity.setReplayPolicy(ProgrammingPolicyProfileSupport.toMap(replay));
		entity.setCompositionPolicy(ProgrammingPolicyProfileSupport.toMap(composition));
		StationProgrammingPolicyEntity saved = policyRepository.save(entity);
		station.setProgrammingEnabled(request.enabled());
		station.setDefaultProgramTemplateId(request.defaultTemplateId());
		stationRepository.save(station);
		ruleRepository.deleteByPolicyId(saved.getId());
		saveRules(saved.getId(), request.rules());
		return new ProgrammingDtos.ProgrammingPolicyResponse(
				stationId,
				saved.getVersion(),
				request.enabled(),
				saved.getDefaultTemplateId(),
				saved.getFallbackStrategy(),
				saved.getPlanningHorizonMinutes(),
				ProgrammingPolicyProfileSupport.toPreGenerationProfile(saved.getPreGenerationPolicy()),
				ProgrammingPolicyProfileSupport.toReplayProfile(saved.getReplayPolicy()),
				ProgrammingPolicyProfileSupport.toCompositionProfile(saved.getCompositionPolicy()),
				saved.getUpdatedAt(),
				getRules(stationId));
	}

	@Transactional(readOnly = true)
	public ProgrammingDtos.ProgrammingPolicyResponse getPolicy(String stationId) {
		StationEntity station = findStation(stationId);
		StationProgrammingPolicyEntity policy = loadPolicy(station);
		return new ProgrammingDtos.ProgrammingPolicyResponse(
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
				getRules(policy));
	}

	@Transactional(readOnly = true)
	public ProgrammingDtos.ProgrammingPreviewResponse preview(String stationId, ProgrammingDtos.ProgrammingPreviewRequest request) {
		findStation(stationId);
		StationProgrammingPolicyEntity policy = policyRepository.findByStationId(stationId).orElse(null);
		List<ProgramRuleEntity> rules = policy == null ? List.of() : ruleRepository.findByPolicyIdOrderByPriorityDesc(policy.getId());
		Map<String, String> providerStates = normalizeProviderStates(request.providerStates());
		Optional<ProgramTemplateEntity> selectedTemplate = selectTemplate(policy, rules, request, providerStates, stationId);
		ProgramTemplateEntity template = selectedTemplate.orElseGet(() -> policy != null && policy.getDefaultTemplateId() != null
				? templateRepository.findById(policy.getDefaultTemplateId()).orElse(null)
				: null);
		boolean fallbackApplied = template == null;
		List<ProgrammingDtos.PreviewSlot> slots = template != null
				? slotRepository.findByProgramTemplateIdOrderBySequenceNoAsc(template.getId()).stream().map(this::toPreviewSlot).toList()
				: ProgrammingSupport.buildLegacyFallbackSlots();
		Integer plannedDurationMs = slots.stream().mapToInt(ProgrammingDtos.PreviewSlot::targetDurationMs).sum();
		return new ProgrammingDtos.ProgrammingPreviewResponse(
				stationId,
				template != null ? template.getId() : "legacy-ratio-fallback",
				fallbackApplied,
				new ProgrammingDtos.PreviewProgram(template != null ? template.getName() : "Legacy Ratio Fallback", plannedDurationMs),
				slots,
				buildWarnings(template, policy, rules));
	}

	private List<ProgrammingDtos.ValidationWarning> buildWarnings(ProgramTemplateEntity template, StationProgrammingPolicyEntity policy, List<ProgramRuleEntity> rules) {
		List<ProgrammingDtos.ValidationWarning> warnings = new ArrayList<>();
		if (template == null) {
			warnings.add(new ProgrammingDtos.ValidationWarning("LEGACY_RATIO_FALLBACK", "番組テンプレートを解決できなかったため固定比率へフォールバックしました。"));
		}
		if (policy == null) {
			warnings.add(new ProgrammingDtos.ValidationWarning("NO_POLICY", "編成ポリシーが未設定です。"));
		} else if (rules.isEmpty()) {
			warnings.add(new ProgrammingDtos.ValidationWarning("NO_RULE", "一致する番組ルールがありません。"));
		}
		return warnings;
	}

	private Optional<ProgramTemplateEntity> selectTemplate(
			StationProgrammingPolicyEntity policy,
			List<ProgramRuleEntity> rules,
			ProgrammingDtos.ProgrammingPreviewRequest request,
			Map<String, String> providerStates,
			String stationId) {
		if (policy == null) {
			return Optional.empty();
		}
		for (ProgramRuleEntity rule : rules) {
			if (matchesRule(rule, request, providerStates) && isTemplateUsable(rule.getTemplateId(), stationId)) {
				return templateRepository.findById(rule.getTemplateId());
			}
		}
		if (policy.getDefaultTemplateId() != null && isTemplateUsable(policy.getDefaultTemplateId(), stationId)) {
			return templateRepository.findById(policy.getDefaultTemplateId());
		}
		return Optional.empty();
	}

	private boolean matchesRule(ProgramRuleEntity rule, ProgrammingDtos.ProgrammingPreviewRequest request, Map<String, String> providerStates) {
		if (request.pendingLetterCount() < rule.getMinimumPendingLetters()) {
			return false;
		}
		if (!ProgrammingSupport.matchesDay(rule.getDaysOfWeek(), request.at().getDayOfWeek())) {
			return false;
		}
		String currentTime = request.at().toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));
		if (!ProgrammingSupport.matchesTime(rule.getStartTime(), rule.getEndTime(), currentTime)) {
			return false;
		}
		for (String required : rule.getRequiredProviderStates()) {
			if (!providerStates.containsKey(required.toUpperCase(Locale.ROOT))) {
				return false;
			}
		}
		return true;
	}

	private Map<String, String> normalizeProviderStates(Map<String, String> providerStates) {
		Map<String, String> normalized = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : providerStates.entrySet()) {
			normalized.put((entry.getKey() + "_" + entry.getValue()).toUpperCase(Locale.ROOT), entry.getValue());
		}
		return normalized;
	}

	private boolean isTemplateUsable(String templateId, String stationId) {
		return templateRepository.findById(templateId)
				.filter(template -> "GLOBAL".equalsIgnoreCase(template.getScope())
						|| ("STATION".equalsIgnoreCase(template.getScope()) && stationId.equals(template.getStationId())))
				.isPresent();
	}

	private void validatePolicyReferences(String stationId, String defaultTemplateId, List<ProgrammingDtos.ProgramRuleRequest> rules) {
		if (defaultTemplateId != null && !isTemplateUsable(defaultTemplateId, stationId)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE", "既定テンプレートが局に対して無効です。", Map.of("defaultTemplateId", defaultTemplateId));
		}
		for (ProgrammingDtos.ProgramRuleRequest rule : rules) {
			if (!isTemplateUsable(rule.templateId(), stationId)) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE", "ルールが参照するテンプレートが無効です。", Map.of("templateId", rule.templateId()));
			}
		}
	}

	private StationEntity findStation(String id) {
		return stationRepository.findById(id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "局が見つかりません。", Map.of("id", id)));
	}

	private StationProgrammingPolicyEntity findPolicy(String stationId) {
		return policyRepository.findByStationId(stationId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "編成ポリシーが見つかりません。", Map.of("stationId", stationId)));
	}

	private StationProgrammingPolicyEntity loadPolicy(StationEntity station) {
		return policyRepository.findByStationId(station.getId())
				.orElseGet(() -> defaultPolicy(station.getId(), station.getDefaultProgramTemplateId()));
	}

	private List<ProgrammingDtos.ProgramRuleDto> getRules(StationProgrammingPolicyEntity policy) {
		return ruleRepository.findByPolicyIdOrderByPriorityDesc(policy.getId()).stream().map(this::toRuleDto).toList();
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

	private ProgramTemplateEntity findTemplate(String id) {
		return templateRepository.findById(id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "番組テンプレートが見つかりません。", Map.of("id", id)));
	}

	private ProgramTemplateEntity buildTemplate(ProgramTemplateEntity existing, ProgrammingDtos.ProgramTemplateRequest request) {
		ProgramTemplateEntity entity = existing != null ? existing : new ProgramTemplateEntity();
		entity.setId(request.id());
		entity.setScope(request.scope().toUpperCase(Locale.ROOT));
		entity.setStationId(request.stationId());
		entity.setName(request.name());
		entity.setVersion(request.version());
		entity.setTargetDurationMinutes(request.targetDurationMinutes());
		entity.setPlanningHorizonMinutes(request.planningHorizonMinutes());
		entity.setActive(request.isActive());
		entity.setEditorialPolicy(request.editorialPolicy() == null ? Map.of() : request.editorialPolicy());
		entity.setFallbackTemplateId(request.fallbackTemplateId());
		return entity;
	}

	private void validateTemplateGraph(ProgramTemplateEntity template, List<ProgrammingDtos.ProgramSlotDto> slots) {
		if (template.getStationId() != null && !stationRepository.existsById(template.getStationId())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "テンプレートの stationId が存在しません。", Map.of("stationId", template.getStationId()));
		}
		if ("STATION".equalsIgnoreCase(template.getScope()) && template.getStationId() == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "STATION スコープのテンプレートには stationId が必要です。", Map.of("id", template.getId()));
		}
		if ("GLOBAL".equalsIgnoreCase(template.getScope()) && template.getStationId() != null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "GLOBAL スコープのテンプレートに stationId は不要です。", Map.of("id", template.getId()));
		}
		if (template.getFallbackTemplateId() != null) {
			ProgramTemplateEntity fallback = templateRepository.findById(template.getFallbackTemplateId())
					.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE", "fallbackTemplateId が存在しません。", Map.of("fallbackTemplateId", template.getFallbackTemplateId())));
			if (template.getFallbackTemplateId().equals(template.getId())) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE", "fallbackTemplateId に自己参照は指定できません。", Map.of("id", template.getId()));
			}
			ProgramTemplateEntity cursor = fallback;
			while (cursor != null) {
				if (template.getId().equals(cursor.getFallbackTemplateId())) {
					throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE", "fallbackTemplateId に循環参照があります。", Map.of("id", template.getId()));
				}
				cursor = cursor.getFallbackTemplateId() == null ? null : templateRepository.findById(cursor.getFallbackTemplateId()).orElse(null);
			}
		}
		if (slots == null || slots.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "テンプレートには少なくとも 1 つのスロットが必要です。", Map.of("id", template.getId()));
		}
		for (ProgrammingDtos.ProgramSlotDto slot : slots) {
			if (slot.candidateSegmentTypes() == null || slot.candidateSegmentTypes().isEmpty()) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "候補 segmentType が空です。", Map.of("slotId", slot.slotId()));
			}
			ProgrammingSupport.validateSegmentTypes(slot.candidateSegmentTypes(), "candidateSegmentTypes", slot.slotId());
			ProgrammingSupport.validateSegmentTypes(slot.fallbackSegmentTypes(), "fallbackSegmentTypes", slot.slotId());
		}
	}

	private void saveSlots(String templateId, List<ProgrammingDtos.ProgramSlotDto> slots) {
		for (int index = 0; index < slots.size(); index++) {
			ProgrammingDtos.ProgramSlotDto slot = slots.get(index);
			ProgramTemplateSlotEntity entity = new ProgramTemplateSlotEntity();
			entity.setId(templateId + "-" + slot.slotId());
			entity.setProgramTemplateId(templateId);
			entity.setSequenceNo(index + 1);
			entity.setRole(slot.role());
			entity.setConstraintMode(slot.constraintMode());
			entity.setCandidateSegmentTypes(slot.candidateSegmentTypes());
			entity.setFallbackSegmentTypes(slot.fallbackSegmentTypes() == null ? List.of() : slot.fallbackSegmentTypes());
			entity.setTargetDurationMs(slot.targetDurationMs());
			entity.setSlotPolicy(slot.slotPolicy() == null ? Map.of() : slot.slotPolicy());
			slotRepository.save(entity);
		}
	}

	private void saveRules(String policyId, List<ProgrammingDtos.ProgramRuleRequest> rules) {
		for (int index = 0; index < rules.size(); index++) {
			ProgrammingDtos.ProgramRuleRequest rule = rules.get(index);
			ProgramRuleEntity entity = new ProgramRuleEntity();
			entity.setId(policyId + "-rule-" + (index + 1));
			entity.setPolicyId(policyId);
			entity.setPriority(rule.priority());
			entity.setDaysOfWeek(String.join(",", ProgrammingSupport.normalizeDays(rule.days())));
			entity.setStartTime(rule.startTime());
			entity.setEndTime(rule.endTime());
			entity.setMinimumPendingLetters(rule.minimumPendingLetters());
			entity.setRequiredProviderStates(rule.requiredProviderStates() == null ? List.of() : rule.requiredProviderStates());
			entity.setTemplateId(rule.templateId());
			ruleRepository.save(entity);
		}
	}

	private ProgrammingDtos.ProgramTemplateSummary toSummary(ProgramTemplateEntity entity) {
		return new ProgrammingDtos.ProgramTemplateSummary(
				entity.getId(),
				entity.getScope(),
				entity.getStationId(),
				entity.getName(),
				entity.getVersion(),
				entity.getTargetDurationMinutes(),
				entity.getPlanningHorizonMinutes(),
				entity.isActive(),
				entity.getFallbackTemplateId());
	}

	private ProgrammingDtos.ProgramTemplateDetail toDetail(ProgramTemplateEntity entity) {
		List<ProgrammingDtos.ProgramSlotDto> slots = slotRepository.findByProgramTemplateIdOrderBySequenceNoAsc(entity.getId()).stream()
				.map(slot -> new ProgrammingDtos.ProgramSlotDto(
						slot.getId().substring(entity.getId().length() + 1),
						slot.getRole(),
						slot.getConstraintMode(),
						slot.getCandidateSegmentTypes(),
						slot.getFallbackSegmentTypes(),
						slot.getTargetDurationMs(),
						slot.getSlotPolicy()))
				.toList();
		return new ProgrammingDtos.ProgramTemplateDetail(
				entity.getId(),
				entity.getScope(),
				entity.getStationId(),
				entity.getName(),
				entity.getVersion(),
				entity.getTargetDurationMinutes(),
				entity.getPlanningHorizonMinutes(),
				entity.isActive(),
				entity.getEditorialPolicy(),
				entity.getFallbackTemplateId(),
				slots);
	}

	private ProgrammingDtos.ProgramRuleDto toRuleDto(ProgramRuleEntity entity) {
		return new ProgrammingDtos.ProgramRuleDto(
				entity.getId(),
				entity.getPriority(),
				List.of(entity.getDaysOfWeek().split(",")),
				entity.getStartTime(),
				entity.getEndTime(),
				entity.getMinimumPendingLetters(),
				entity.getRequiredProviderStates(),
				entity.getTemplateId());
	}

	private ProgrammingDtos.PreviewSlot toPreviewSlot(ProgramTemplateSlotEntity entity) {
		return new ProgrammingDtos.PreviewSlot(entity.getId(), entity.getRole(), entity.getConstraintMode(), entity.getTargetDurationMs());
	}
}
