package com.seedshiftradio.station;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seedshiftradio.common.config.OpenApiConfig;
import com.seedshiftradio.common.security.AdminApiGuard;
import com.seedshiftradio.programming.ProgrammingAdminService;
import com.seedshiftradio.programming.ProgrammingDtos;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api")
public class StationController {

	private final StationAdminService stationAdminService;
	private final ProgrammingAdminService programmingAdminService;
	private final AdminApiGuard adminApiGuard;

	public StationController(
			StationAdminService stationAdminService,
			ProgrammingAdminService programmingAdminService,
			AdminApiGuard adminApiGuard) {
		this.stationAdminService = stationAdminService;
		this.programmingAdminService = programmingAdminService;
		this.adminApiGuard = adminApiGuard;
	}

	@GetMapping("/stations")
	public List<StationDtos.StationSummary> listStations() {
		return stationAdminService.listStations();
	}

	@GetMapping("/stations/{id}")
	public StationDtos.StationDetail getStation(@PathVariable("id") String stationId) {
		return stationAdminService.getStation(stationId);
	}

	@PostMapping("/stations")
	@SecurityRequirement(name = OpenApiConfig.ADMIN_SECURITY_SCHEME)
	public StationDtos.StationResponse createStation(
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken,
			@Valid @RequestBody StationDtos.StationUpsertRequest request) {
		adminApiGuard.require(adminToken);
		return stationAdminService.createStation(request);
	}

	@PutMapping("/stations/{id}")
	@SecurityRequirement(name = OpenApiConfig.ADMIN_SECURITY_SCHEME)
	public StationDtos.StationResponse updateStation(
			@PathVariable("id") String stationId,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken,
			@Valid @RequestBody StationDtos.StationUpsertRequest request) {
		adminApiGuard.require(adminToken);
		return stationAdminService.updateStation(stationId, request);
	}

	@GetMapping("/stations/{id}/programming")
	@SecurityRequirement(name = OpenApiConfig.ADMIN_SECURITY_SCHEME)
	public ProgrammingDtos.ProgrammingPolicyResponse getProgramming(
			@PathVariable("id") String stationId,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken) {
		adminApiGuard.require(adminToken);
		return programmingAdminService.getPolicy(stationId);
	}

	@PutMapping("/stations/{id}/programming")
	@SecurityRequirement(name = OpenApiConfig.ADMIN_SECURITY_SCHEME)
	public ProgrammingDtos.ProgrammingPolicyResponse updateProgramming(
			@PathVariable("id") String stationId,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken,
			@Valid @RequestBody ProgrammingDtos.ProgrammingPolicyRequest request) {
		adminApiGuard.require(adminToken);
		return programmingAdminService.savePolicy(stationId, request);
	}

	@PostMapping("/stations/{id}/programming/preview")
	@SecurityRequirement(name = OpenApiConfig.ADMIN_SECURITY_SCHEME)
	public ProgrammingDtos.ProgrammingPreviewResponse previewProgramming(
			@PathVariable("id") String stationId,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken,
			@Valid @RequestBody ProgrammingDtos.ProgrammingPreviewRequest request) {
		adminApiGuard.require(adminToken);
		return programmingAdminService.preview(stationId, request);
	}
}
