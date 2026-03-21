package com.seedshiftradio.programming;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seedshiftradio.common.security.AdminApiGuard;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/program-templates")
public class ProgramTemplateController {

	private final AdminApiGuard adminApiGuard;
	private final ProgrammingAdminService programmingAdminService;

	public ProgramTemplateController(AdminApiGuard adminApiGuard, ProgrammingAdminService programmingAdminService) {
		this.adminApiGuard = adminApiGuard;
		this.programmingAdminService = programmingAdminService;
	}

	@GetMapping
	public List<ProgrammingDtos.ProgramTemplateSummary> listTemplates(@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken) {
		adminApiGuard.require(adminToken);
		return programmingAdminService.listTemplates();
	}

	@GetMapping("/{id}")
	public ProgrammingDtos.ProgramTemplateDetail getTemplate(
			@PathVariable String id,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken) {
		adminApiGuard.require(adminToken);
		return programmingAdminService.getTemplate(id);
	}

	@PostMapping
	public ProgrammingDtos.ProgramTemplateDetail createTemplate(
			@Valid @RequestBody ProgrammingDtos.ProgramTemplateRequest request,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken) {
		adminApiGuard.require(adminToken);
		return programmingAdminService.createTemplate(request);
	}

	@PutMapping("/{id}")
	public ProgrammingDtos.ProgramTemplateDetail updateTemplate(
			@PathVariable String id,
			@Valid @RequestBody ProgrammingDtos.ProgramTemplateRequest request,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken) {
		adminApiGuard.require(adminToken);
		return programmingAdminService.updateTemplate(id, request);
	}
}
