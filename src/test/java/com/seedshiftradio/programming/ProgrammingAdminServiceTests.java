package com.seedshiftradio.programming;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
