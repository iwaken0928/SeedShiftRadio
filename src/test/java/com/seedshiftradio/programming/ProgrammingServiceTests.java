package com.seedshiftradio.programming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.ConstraintMode;
import com.seedshiftradio.domain.LetterStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.letter.LetterRepository;
import com.seedshiftradio.settings.ProviderHealthService;
import com.seedshiftradio.station.PersonalityRepository;
import com.seedshiftradio.station.StationEntity;
import com.seedshiftradio.station.StationRepository;
import com.seedshiftradio.station.VoiceProfileRepository;

@ExtendWith(MockitoExtension.class)
class ProgrammingServiceTests {

	@Mock
	StationRepository stationRepository;

	@Mock
	PersonalityRepository personalityRepository;

	@Mock
	VoiceProfileRepository voiceProfileRepository;

	@Mock
	StationProgrammingPolicyRepository policyRepository;

	@Mock
	ProgramRuleRepository ruleRepository;

	@Mock
	ProgramTemplateRepository templateRepository;

	@Mock
	ProgramTemplateSlotRepository slotRepository;

	@Mock
	LetterRepository letterRepository;

	@Mock
	ProviderHealthService providerHealthService;

	ProgrammingService programmingService;

	@BeforeEach
	void setUp() {
		programmingService = new ProgrammingService(
				stationRepository,
				personalityRepository,
				voiceProfileRepository,
				policyRepository,
				ruleRepository,
				templateRepository,
				slotRepository,
				letterRepository,
				providerHealthService);
	}

	@Test
	void resolveCurrentPlanUsesMeasuredProviderHealthAndChoosesDefaultTemplate() {
		StationEntity station = station();
		StationProgrammingPolicyEntity policy = policy("policy-station-night", station.getId(), "tmpl-default");
		ProgramRuleEntity rule = rule("rule-night", policy.getId(), "tmpl-rule");
		ProgramTemplateEntity defaultTemplate = template("tmpl-default", "Night Default");
		ProgramTemplateSlotEntity defaultSlot = slot("slot-default", defaultTemplate.getId());

		when(stationRepository.findById(station.getId())).thenReturn(Optional.of(station));
		when(policyRepository.findByStationId(station.getId())).thenReturn(Optional.of(policy));
		when(ruleRepository.findByPolicyIdOrderByPriorityDesc(policy.getId())).thenReturn(List.of(rule));
		when(providerHealthService.getLatestOrProbe()).thenReturn(health("DOWN", "UP", "UP"));
		when(letterRepository.countByStationIdAndStatusIn(anyString(), any())).thenReturn(0L);
		when(templateRepository.findById("tmpl-default")).thenReturn(Optional.of(defaultTemplate));
		when(slotRepository.findByProgramTemplateIdOrderBySequenceNoAsc("tmpl-default")).thenReturn(List.of(defaultSlot));

		ProgrammingService.ResolvedProgramPlan plan = programmingService.resolveCurrentPlan(
				station.getId(),
				OffsetDateTime.parse("2026-03-20T23:30:00+09:00"));

		assertEquals("tmpl-default", plan.templateId());
		assertFalse(plan.fallbackApplied());
		assertEquals("Night Default", plan.title());
		assertEquals(SegmentType.TALK, plan.slots().getFirst().resolvedSegmentType());
		verify(providerHealthService).getLatestOrProbe();
		verify(letterRepository).countByStationIdAndStatusIn(station.getId(), EnumSet.of(LetterStatus.UNREAD, LetterStatus.PENDING));
		verify(templateRepository, never()).findById("tmpl-rule");
	}

	@Test
	void resolveCurrentPlanFlagsFallbackWhenSlotFallsBackDueToProviderHealth() {
		StationEntity station = station();
		StationProgrammingPolicyEntity policy = policy("policy-station-night", station.getId(), "tmpl-default");
		ProgramRuleEntity rule = rule("rule-night", policy.getId(), "tmpl-rule");
		rule.setRequiredProviderStates(List.of());
		ProgramTemplateEntity selectedTemplate = template("tmpl-rule", "Night AI");
		ProgramTemplateSlotEntity slot = slot("slot-ai", selectedTemplate.getId());
		slot.setCandidateSegmentTypes(List.of("MUSIC_AI"));
		slot.setFallbackSegmentTypes(List.of("MUSIC_LOCAL"));

		when(stationRepository.findById(station.getId())).thenReturn(Optional.of(station));
		when(policyRepository.findByStationId(station.getId())).thenReturn(Optional.of(policy));
		when(ruleRepository.findByPolicyIdOrderByPriorityDesc(policy.getId())).thenReturn(List.of(rule));
		when(providerHealthService.getLatestOrProbe()).thenReturn(health("DOWN", "UP", "UP"));
		when(letterRepository.countByStationIdAndStatusIn(anyString(), any())).thenReturn(0L);
		when(templateRepository.findById("tmpl-rule")).thenReturn(Optional.of(selectedTemplate));
		when(slotRepository.findByProgramTemplateIdOrderBySequenceNoAsc("tmpl-rule")).thenReturn(List.of(slot));

		ProgrammingService.ResolvedProgramPlan plan = programmingService.resolveCurrentPlan(
				station.getId(),
				OffsetDateTime.parse("2026-03-20T23:30:00+09:00"));

		assertEquals("tmpl-rule", plan.templateId());
		assertTrue(plan.fallbackApplied());
		assertEquals(SegmentType.MUSIC_LOCAL, plan.slots().getFirst().resolvedSegmentType());
		assertTrue(plan.validationWarnings().stream().anyMatch(message -> message.contains("fallback")));
	}

	@Test
	void resolveCurrentPlanPrefersLetterWhenPendingLettersReachCompositionThreshold() {
		StationEntity station = station();
		StationProgrammingPolicyEntity policy = policy("policy-station-night", station.getId(), "tmpl-default");
		policy.setCompositionPolicy(ProgrammingPolicyProfileSupport.toMap(
				new ProgrammingPolicyProfileSupport.CompositionProfile(
						Map.of("talk", 40, "letter", 20, "music", 35, "jingle", 5),
						2,
						8,
						3,
						true)));
		ProgramTemplateEntity template = template("tmpl-default", "Night Default");
		ProgramTemplateSlotEntity slot = slot("slot-letter-priority", template.getId());
		slot.setCandidateSegmentTypes(List.of("TALK", "LETTER"));

		when(stationRepository.findById(station.getId())).thenReturn(Optional.of(station));
		when(policyRepository.findByStationId(station.getId())).thenReturn(Optional.of(policy));
		when(ruleRepository.findByPolicyIdOrderByPriorityDesc(policy.getId())).thenReturn(List.of());
		when(providerHealthService.getLatestOrProbe()).thenReturn(health("UP", "UP", "UP"));
		when(letterRepository.countByStationIdAndStatusIn(anyString(), any())).thenReturn(3L);
		when(templateRepository.findById("tmpl-default")).thenReturn(Optional.of(template));
		when(slotRepository.findByProgramTemplateIdOrderBySequenceNoAsc("tmpl-default")).thenReturn(List.of(slot));

		ProgrammingService.ResolvedProgramPlan plan = programmingService.resolveCurrentPlan(
				station.getId(),
				OffsetDateTime.parse("2026-03-20T23:30:00+09:00"));

		assertEquals(SegmentType.LETTER, plan.slots().getFirst().resolvedSegmentType());
		assertTrue(plan.validationWarnings().stream().anyMatch(message -> message.contains("letterPriorityBoostThreshold")));
	}

	@Test
	void resolveCurrentPlanDoesNotRetimingLettersWhenSoftFallbackRetimingDisabled() {
		StationEntity station = station();
		StationProgrammingPolicyEntity policy = policy("policy-station-night", station.getId(), "tmpl-default");
		policy.setCompositionPolicy(ProgrammingPolicyProfileSupport.toMap(
				new ProgrammingPolicyProfileSupport.CompositionProfile(
						Map.of("talk", 40, "letter", 20, "music", 35, "jingle", 5),
						2,
						8,
						3,
						false)));
		ProgramTemplateEntity template = template("tmpl-default", "Night Default");
		ProgramTemplateSlotEntity slot = slot("slot-letter-priority", template.getId());
		slot.setCandidateSegmentTypes(List.of("TALK", "LETTER"));

		when(stationRepository.findById(station.getId())).thenReturn(Optional.of(station));
		when(policyRepository.findByStationId(station.getId())).thenReturn(Optional.of(policy));
		when(ruleRepository.findByPolicyIdOrderByPriorityDesc(policy.getId())).thenReturn(List.of());
		when(providerHealthService.getLatestOrProbe()).thenReturn(health("UP", "UP", "UP"));
		when(letterRepository.countByStationIdAndStatusIn(anyString(), any())).thenReturn(3L);
		when(templateRepository.findById("tmpl-default")).thenReturn(Optional.of(template));
		when(slotRepository.findByProgramTemplateIdOrderBySequenceNoAsc("tmpl-default")).thenReturn(List.of(slot));

		ProgrammingService.ResolvedProgramPlan plan = programmingService.resolveCurrentPlan(
				station.getId(),
				OffsetDateTime.parse("2026-03-20T23:30:00+09:00"));

		assertEquals(SegmentType.TALK, plan.slots().getFirst().resolvedSegmentType());
		assertTrue(plan.validationWarnings().stream().noneMatch(message -> message.contains("letterPriorityBoostThreshold")));
	}

	@Test
	void resolveCurrentPlanBreaksTalkStreakWhenCompositionLimitIsReached() {
		StationEntity station = station();
		StationProgrammingPolicyEntity policy = policy("policy-station-night", station.getId(), "tmpl-default");
		policy.setCompositionPolicy(ProgrammingPolicyProfileSupport.toMap(
				new ProgrammingPolicyProfileSupport.CompositionProfile(
						Map.of("talk", 40, "letter", 20, "music", 35, "jingle", 5),
						1,
						8,
						4,
						true)));
		ProgramTemplateEntity template = template("tmpl-default", "Night Default");
		ProgramTemplateSlotEntity first = slot("slot-talk-1", template.getId());
		first.setCandidateSegmentTypes(List.of("TALK"));
		ProgramTemplateSlotEntity second = slot("slot-talk-2", template.getId());
		second.setSequenceNo(2);
		second.setCandidateSegmentTypes(List.of("TALK", "MUSIC_LOCAL"));

		when(stationRepository.findById(station.getId())).thenReturn(Optional.of(station));
		when(policyRepository.findByStationId(station.getId())).thenReturn(Optional.of(policy));
		when(ruleRepository.findByPolicyIdOrderByPriorityDesc(policy.getId())).thenReturn(List.of());
		when(providerHealthService.getLatestOrProbe()).thenReturn(health("UP", "UP", "UP"));
		when(letterRepository.countByStationIdAndStatusIn(anyString(), any())).thenReturn(0L);
		when(templateRepository.findById("tmpl-default")).thenReturn(Optional.of(template));
		when(slotRepository.findByProgramTemplateIdOrderBySequenceNoAsc("tmpl-default")).thenReturn(List.of(first, second));

		ProgrammingService.ResolvedProgramPlan plan = programmingService.resolveCurrentPlan(
				station.getId(),
				OffsetDateTime.parse("2026-03-20T23:30:00+09:00"));

		assertEquals(List.of(SegmentType.TALK, SegmentType.MUSIC_LOCAL), plan.slots().stream().map(ProgrammingService.ResolvedSlot::resolvedSegmentType).toList());
		assertTrue(plan.validationWarnings().stream().anyMatch(message -> message.contains("maxConsecutiveTalkSegments")));
	}

	@Test
	void resolveCurrentPlanKeepsTalkStreakWhenSoftFallbackRetimingDisabled() {
		StationEntity station = station();
		StationProgrammingPolicyEntity policy = policy("policy-station-night", station.getId(), "tmpl-default");
		policy.setCompositionPolicy(ProgrammingPolicyProfileSupport.toMap(
				new ProgrammingPolicyProfileSupport.CompositionProfile(
						Map.of("talk", 40, "letter", 20, "music", 35, "jingle", 5),
						1,
						8,
						4,
						false)));
		ProgramTemplateEntity template = template("tmpl-default", "Night Default");
		ProgramTemplateSlotEntity first = slot("slot-talk-1", template.getId());
		first.setCandidateSegmentTypes(List.of("TALK"));
		ProgramTemplateSlotEntity second = slot("slot-talk-2", template.getId());
		second.setSequenceNo(2);
		second.setCandidateSegmentTypes(List.of("TALK", "MUSIC_LOCAL"));

		when(stationRepository.findById(station.getId())).thenReturn(Optional.of(station));
		when(policyRepository.findByStationId(station.getId())).thenReturn(Optional.of(policy));
		when(ruleRepository.findByPolicyIdOrderByPriorityDesc(policy.getId())).thenReturn(List.of());
		when(providerHealthService.getLatestOrProbe()).thenReturn(health("UP", "UP", "UP"));
		when(letterRepository.countByStationIdAndStatusIn(anyString(), any())).thenReturn(0L);
		when(templateRepository.findById("tmpl-default")).thenReturn(Optional.of(template));
		when(slotRepository.findByProgramTemplateIdOrderBySequenceNoAsc("tmpl-default")).thenReturn(List.of(first, second));

		ProgrammingService.ResolvedProgramPlan plan = programmingService.resolveCurrentPlan(
				station.getId(),
				OffsetDateTime.parse("2026-03-20T23:30:00+09:00"));

		assertEquals(List.of(SegmentType.TALK, SegmentType.TALK), plan.slots().stream().map(ProgrammingService.ResolvedSlot::resolvedSegmentType).toList());
		assertTrue(plan.validationWarnings().stream().noneMatch(message -> message.contains("maxConsecutiveTalkSegments")));
	}

	@Test
	void resolveCurrentPlanPrefersMusicBreakWhenCompositionIntervalIsExceeded() {
		StationEntity station = station();
		StationProgrammingPolicyEntity policy = policy("policy-station-night", station.getId(), "tmpl-default");
		policy.setCompositionPolicy(ProgrammingPolicyProfileSupport.toMap(
				new ProgrammingPolicyProfileSupport.CompositionProfile(
						Map.of("talk", 40, "letter", 20, "music", 35, "jingle", 5),
						3,
						1,
						4,
						true)));
		ProgramTemplateEntity template = template("tmpl-default", "Night Default");
		ProgramTemplateSlotEntity first = slot("slot-talk-1", template.getId());
		first.setCandidateSegmentTypes(List.of("TALK"));
		first.setTargetDurationMs(45_000);
		ProgramTemplateSlotEntity second = slot("slot-talk-2", template.getId());
		second.setSequenceNo(2);
		second.setCandidateSegmentTypes(List.of("TALK"));
		second.setTargetDurationMs(30_000);
		ProgramTemplateSlotEntity third = slot("slot-break", template.getId());
		third.setSequenceNo(3);
		third.setCandidateSegmentTypes(List.of("TALK", "MUSIC_LOCAL"));
		third.setTargetDurationMs(30_000);

		when(stationRepository.findById(station.getId())).thenReturn(Optional.of(station));
		when(policyRepository.findByStationId(station.getId())).thenReturn(Optional.of(policy));
		when(ruleRepository.findByPolicyIdOrderByPriorityDesc(policy.getId())).thenReturn(List.of());
		when(providerHealthService.getLatestOrProbe()).thenReturn(health("UP", "UP", "UP"));
		when(letterRepository.countByStationIdAndStatusIn(anyString(), any())).thenReturn(0L);
		when(templateRepository.findById("tmpl-default")).thenReturn(Optional.of(template));
		when(slotRepository.findByProgramTemplateIdOrderBySequenceNoAsc("tmpl-default")).thenReturn(List.of(first, second, third));

		ProgrammingService.ResolvedProgramPlan plan = programmingService.resolveCurrentPlan(
				station.getId(),
				OffsetDateTime.parse("2026-03-20T23:30:00+09:00"));

		assertEquals(List.of(SegmentType.TALK, SegmentType.TALK, SegmentType.MUSIC_LOCAL), plan.slots().stream().map(ProgrammingService.ResolvedSlot::resolvedSegmentType).toList());
		assertTrue(plan.validationWarnings().stream().anyMatch(message -> message.contains("musicBreakIntervalMinutes")));
	}

	@Test
	void resolveCurrentPlanKeepsCurrentCandidateWhenSoftFallbackRetimingDisabled() {
		StationEntity station = station();
		StationProgrammingPolicyEntity policy = policy("policy-station-night", station.getId(), "tmpl-default");
		policy.setCompositionPolicy(ProgrammingPolicyProfileSupport.toMap(
				new ProgrammingPolicyProfileSupport.CompositionProfile(
						Map.of("talk", 40, "letter", 20, "music", 35, "jingle", 5),
						3,
						1,
						4,
						false)));
		ProgramTemplateEntity template = template("tmpl-default", "Night Default");
		ProgramTemplateSlotEntity first = slot("slot-talk-1", template.getId());
		first.setCandidateSegmentTypes(List.of("TALK"));
		first.setTargetDurationMs(45_000);
		ProgramTemplateSlotEntity second = slot("slot-talk-2", template.getId());
		second.setSequenceNo(2);
		second.setCandidateSegmentTypes(List.of("TALK"));
		second.setTargetDurationMs(30_000);
		ProgramTemplateSlotEntity third = slot("slot-break", template.getId());
		third.setSequenceNo(3);
		third.setCandidateSegmentTypes(List.of("TALK", "MUSIC_LOCAL"));
		third.setTargetDurationMs(30_000);

		when(stationRepository.findById(station.getId())).thenReturn(Optional.of(station));
		when(policyRepository.findByStationId(station.getId())).thenReturn(Optional.of(policy));
		when(ruleRepository.findByPolicyIdOrderByPriorityDesc(policy.getId())).thenReturn(List.of());
		when(providerHealthService.getLatestOrProbe()).thenReturn(health("UP", "UP", "UP"));
		when(letterRepository.countByStationIdAndStatusIn(anyString(), any())).thenReturn(0L);
		when(templateRepository.findById("tmpl-default")).thenReturn(Optional.of(template));
		when(slotRepository.findByProgramTemplateIdOrderBySequenceNoAsc("tmpl-default")).thenReturn(List.of(first, second, third));

		ProgrammingService.ResolvedProgramPlan plan = programmingService.resolveCurrentPlan(
				station.getId(),
				OffsetDateTime.parse("2026-03-20T23:30:00+09:00"));

		assertEquals(List.of(SegmentType.TALK, SegmentType.TALK, SegmentType.TALK), plan.slots().stream().map(ProgrammingService.ResolvedSlot::resolvedSegmentType).toList());
		assertTrue(plan.validationWarnings().stream().noneMatch(message -> message.contains("musicBreakIntervalMinutes")));
	}

	private StationEntity station() {
		StationEntity station = new StationEntity(
				"station-night",
				"Midnight Echo",
				new BigDecimal("81.3"),
				"talk",
				"persona-night-main",
				"voice-night-main",
				true,
				"tmpl-default",
				true);
		station.setVersion(1);
		return station;
	}

	private StationProgrammingPolicyEntity policy(String id, String stationId, String defaultTemplateId) {
		StationProgrammingPolicyEntity policy = new StationProgrammingPolicyEntity();
		policy.setId(id);
		policy.setStationId(stationId);
		policy.setVersion(1);
		policy.setDefaultTemplateId(defaultTemplateId);
		policy.setFallbackStrategy("LEGACY_RATIO");
		policy.setPlanningHorizonMinutes(20);
		return policy;
	}

	private ProgramRuleEntity rule(String id, String policyId, String templateId) {
		ProgramRuleEntity rule = new ProgramRuleEntity();
		rule.setId(id);
		rule.setPolicyId(policyId);
		rule.setPriority(100);
		rule.setDaysOfWeek("MON,TUE,WED,THU,FRI,SAT,SUN");
		rule.setStartTime("00:00");
		rule.setEndTime("23:59");
		rule.setMinimumPendingLetters(0);
		rule.setRequiredProviderStates(List.of("MUSICGEN_UP"));
		rule.setTemplateId(templateId);
		return rule;
	}

	private ProgramTemplateEntity template(String id, String name) {
		ProgramTemplateEntity template = new ProgramTemplateEntity();
		template.setId(id);
		template.setScope("STATION");
		template.setStationId("station-night");
		template.setName(name);
		template.setVersion(1);
		template.setTargetDurationMinutes(20);
		template.setPlanningHorizonMinutes(20);
		template.setActive(true);
		template.setEditorialPolicy(Map.of());
		return template;
	}

	private ProgramTemplateSlotEntity slot(String id, String templateId) {
		ProgramTemplateSlotEntity slot = new ProgramTemplateSlotEntity();
		slot.setId(id);
		slot.setProgramTemplateId(templateId);
		slot.setSequenceNo(1);
		slot.setRole(SlotRole.TOPIC);
		slot.setConstraintMode(ConstraintMode.SOFT);
		slot.setCandidateSegmentTypes(List.of("TALK"));
		slot.setFallbackSegmentTypes(List.of("TALK"));
		slot.setTargetDurationMs(30_000);
		slot.setSlotPolicy(Map.of());
		return slot;
	}

	private Map<String, com.seedshiftradio.settings.SettingsDtos.ProviderHealthPayload> health(
			String musicGenStatus,
			String ttsStatus,
			String llmStatus) {
		return Map.of(
				"musicGen", payload("musicGen", musicGenStatus),
				"tts", payload("tts", ttsStatus),
				"llm", payload("llm", llmStatus));
	}

	private com.seedshiftradio.settings.SettingsDtos.ProviderHealthPayload payload(String providerType, String status) {
		return new com.seedshiftradio.settings.SettingsDtos.ProviderHealthPayload(
				providerType,
				providerType + "-default",
				status,
				java.time.Instant.parse("2026-03-20T00:00:00Z"),
				1L,
				status,
				List.of(providerType.toUpperCase()),
				"http://127.0.0.1");
	}
}
