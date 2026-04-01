package com.seedshiftradio.programming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.ConstraintMode;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.station.StationEntity;
import com.seedshiftradio.station.StationRepository;

@ExtendWith(MockitoExtension.class)
class ProgrammingAdminServiceTests {

	@Mock
	StationRepository stationRepository;

	@Mock
	ProgramTemplateRepository templateRepository;

	@Mock
	ProgramTemplateSlotRepository slotRepository;

	@Mock
	StationProgrammingPolicyRepository policyRepository;

	@Mock
	ProgramRuleRepository ruleRepository;

	ProgrammingAdminService programmingAdminService;

	@BeforeEach
	void setUp() {
		programmingAdminService = new ProgrammingAdminService(
				stationRepository,
				templateRepository,
				slotRepository,
				policyRepository,
				ruleRepository);
	}

	@Test
	void createTemplateRejectsUnknownCandidateSegmentType() {
		when(templateRepository.existsById("tmpl-invalid")).thenReturn(false);

		ApiException exception = assertThrows(
				ApiException.class,
				() -> programmingAdminService.createTemplate(new ProgrammingDtos.ProgramTemplateRequest(
						"tmpl-invalid",
						"GLOBAL",
						null,
						"Invalid Template",
						1,
						20,
						10,
						true,
						Map.of(),
						null,
						List.of(new ProgrammingDtos.ProgramSlotDto(
								"open",
								SlotRole.OPENING,
								ConstraintMode.HARD,
								List.of("NOT_A_SEGMENT"),
								List.of("TALK"),
								30_000,
								Map.of())))));

		assertEquals("VALIDATION_ERROR", exception.getCode());
	}

	@Test
	void previewUsesDefaultTemplateWithoutFlaggingFallback() {
		StationEntity station = station("station-night");
		StationProgrammingPolicyEntity policy = policy("policy-station-night", "station-night", "tmpl-default");
		ProgramRuleEntity rule = rule("rule-night", policy.getId(), "tmpl-rule");
		ProgramTemplateEntity defaultTemplate = template("tmpl-default", "Night Default");
		ProgramTemplateSlotEntity defaultSlot = slot("slot-default", defaultTemplate.getId());

		when(stationRepository.findById("station-night")).thenReturn(java.util.Optional.of(station));
		when(policyRepository.findByStationId("station-night")).thenReturn(java.util.Optional.of(policy));
		when(ruleRepository.findByPolicyIdOrderByPriorityDesc(policy.getId())).thenReturn(List.of(rule));
		when(templateRepository.findById("tmpl-default")).thenReturn(java.util.Optional.of(defaultTemplate));
		when(slotRepository.findByProgramTemplateIdOrderBySequenceNoAsc("tmpl-default")).thenReturn(List.of(defaultSlot));

		ProgrammingDtos.ProgrammingPreviewResponse response = programmingAdminService.preview(
				"station-night",
				new ProgrammingDtos.ProgrammingPreviewRequest(
						java.time.OffsetDateTime.parse("2026-03-20T23:30:00+09:00"),
						0,
						Map.of(
								"musicGen", "DOWN",
								"tts", "UP",
								"llm", "UP")));

		assertEquals("station-night", response.stationId());
		assertEquals("tmpl-default", response.selectedTemplateId());
		assertFalse(response.fallbackApplied());
		assertEquals("Night Default", response.program().title());
		assertEquals("slot-default", response.slots().getFirst().slotId());
	}

	@Test
	void getPolicyReturnsDefaultRuntimeProfilesWhenPolicyIsMissing() {
		StationEntity station = station("station-night");

		when(stationRepository.findById("station-night")).thenReturn(java.util.Optional.of(station));
		when(policyRepository.findByStationId("station-night")).thenReturn(java.util.Optional.empty());
		when(ruleRepository.findByPolicyIdOrderByPriorityDesc("policy-station-night")).thenReturn(List.of());

		ProgrammingDtos.ProgrammingPolicyResponse response = programmingAdminService.getPolicy("station-night");

		assertEquals("station-night", response.stationId());
		assertEquals("ASSISTED", response.preGeneration().mode());
		assertEquals(List.of("MUSIC_AI", "MUSIC_LOCAL", "JINGLE"), response.replay().eligibleSegmentTypes());
		assertEquals(40, response.composition().targetSegmentShares().get("talk"));
	}

	private StationEntity station(String id) {
		StationEntity station = new StationEntity(
				id,
				"Midnight Echo",
				new java.math.BigDecimal("81.3"),
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
}
