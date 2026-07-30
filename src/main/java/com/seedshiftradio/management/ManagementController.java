package com.seedshiftradio.management;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.seedshiftradio.common.config.OpenApiConfig;
import com.seedshiftradio.common.security.AdminApiGuard;
import com.seedshiftradio.management.ManagementDtos.ManagementDashboardResponse;
import com.seedshiftradio.management.ManagementDtos.PreGenerationRequest;
import com.seedshiftradio.management.ManagementDtos.PreGenerationResponse;
import com.seedshiftradio.management.ManagementDtos.StationContentDeletionRequest;
import com.seedshiftradio.management.ManagementDtos.StationContentDeletionResponse;
import com.seedshiftradio.management.ManagementDtos.StationContentInventory;
import com.seedshiftradio.management.ManagementDtos.StationProgramContentResponse;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/management")
@SecurityRequirement(name = OpenApiConfig.ADMIN_SECURITY_SCHEME)
public class ManagementController {

	private final ManagementService managementService;
	private final AdminApiGuard adminApiGuard;

	public ManagementController(ManagementService managementService, AdminApiGuard adminApiGuard) {
		this.managementService = managementService;
		this.adminApiGuard = adminApiGuard;
	}

	@GetMapping("/dashboard")
	public ManagementDashboardResponse dashboard(
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken) {
		adminApiGuard.require(adminToken);
		return managementService.dashboard();
	}

	@GetMapping("/stations/{stationId}/content")
	public StationContentInventory stationContent(
			@PathVariable String stationId,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken) {
		adminApiGuard.require(adminToken);
		return managementService.stationInventory(stationId);
	}

	@GetMapping("/stations/{stationId}/content/programs")
	public StationProgramContentResponse stationPrograms(
			@PathVariable String stationId,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken) {
		adminApiGuard.require(adminToken);
		return managementService.stationPrograms(stationId);
	}

	@PostMapping("/stations/{stationId}/pre-generations")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public PreGenerationResponse requestPreGeneration(
			@PathVariable String stationId,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken,
			@Valid @RequestBody PreGenerationRequest request) {
		adminApiGuard.require(adminToken);
		return managementService.requestPreGeneration(stationId, request);
	}

	@PostMapping("/stations/{stationId}/content/deletions")
	public StationContentDeletionResponse deleteStationContent(
			@PathVariable String stationId,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken,
			@Valid @RequestBody StationContentDeletionRequest request) {
		adminApiGuard.require(adminToken);
		return managementService.deleteStationContent(stationId, request);
	}

	@PostMapping("/stations/{stationId}/programs/{programBlockId}/content/deletions")
	public StationContentDeletionResponse deleteProgramContent(
			@PathVariable String stationId,
			@PathVariable String programBlockId,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken,
			@Valid @RequestBody StationContentDeletionRequest request) {
		adminApiGuard.require(adminToken);
		return managementService.deleteProgramContent(stationId, programBlockId, request);
	}
}
