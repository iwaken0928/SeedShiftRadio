package com.seedshiftradio.management;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.seedshiftradio.common.security.AdminApiGuard;
import com.seedshiftradio.domain.PreGenerationRequestStatus;
import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.management.ManagementDtos.PreGenerationResponse;
import com.seedshiftradio.management.ManagementDtos.StationContentDeletionResponse;

@ExtendWith(MockitoExtension.class)
class ManagementControllerTests {

	@Mock
	ManagementService managementService;

	@Mock
	AdminApiGuard adminApiGuard;

	MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new ManagementController(managementService, adminApiGuard)).build();
	}

	@Test
	void preGenerationAcceptsProtectedRequestWithoutReturningSensitiveContent() throws Exception {
		when(managementService.requestPreGeneration(
				org.mockito.ArgumentMatchers.eq("station-night"),
				any(ManagementDtos.PreGenerationRequest.class)))
				.thenReturn(response());

		mockMvc.perform(post("/api/management/stations/station-night/pre-generations")
						.header(AdminApiGuard.HEADER_NAME, "test-admin-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "programTemplateId": "tmpl-night",
								  "targetProgramCount": 2,
								  "includeSpeech": true,
								  "includeMusic": true
								}
								"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.id").value("pregen-001"))
				.andExpect(jsonPath("$.stationId").value("station-night"))
				.andExpect(jsonPath("$.programTemplateId").value("tmpl-night"))
				.andExpect(jsonPath("$.targetProgramCount").value(2))
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.prompt").doesNotExist())
				.andExpect(jsonPath("$.lyrics").doesNotExist());

		verify(adminApiGuard).require("test-admin-token");
	}

	@Test
	void stationContentDeletionRequiresAdminAndReturnsDeletionSummary() throws Exception {
		when(managementService.deleteStationContent(
				org.mockito.ArgumentMatchers.eq("station-night"),
				any(ManagementDtos.StationContentDeletionRequest.class)))
				.thenReturn(new StationContentDeletionResponse(
						"station-night",
						Instant.parse("2026-07-28T12:00:00Z"),
						4,
						4,
						0,
						8_192L,
						java.util.Map.of(GeneratedAssetType.AUDIO, 3, GeneratedAssetType.MUSIC, 1)));

		mockMvc.perform(post("/api/management/stations/station-night/content/deletions")
						.header(AdminApiGuard.HEADER_NAME, "test-admin-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "assetTypes": ["AUDIO", "MUSIC"]
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stationId").value("station-night"))
				.andExpect(jsonPath("$.deletedAssetCount").value(4))
				.andExpect(jsonPath("$.reclaimedBytes").value(8192))
				.andExpect(jsonPath("$.deletedByType.AUDIO").value(3))
				.andExpect(jsonPath("$.deletedByType.MUSIC").value(1));

		verify(adminApiGuard).require("test-admin-token");
	}

	private PreGenerationResponse response() {
		Instant now = Instant.parse("2026-07-26T01:00:00Z");
		return new PreGenerationResponse(
				"pregen-001",
				"station-night",
				"playout-pregen-001",
				"tmpl-night",
				2,
				true,
				true,
				PreGenerationRequestStatus.QUEUED,
				0,
				0,
				0,
				null,
				now,
				null,
				null,
				now);
	}
}
