package com.seedshiftradio.settings;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seedshiftradio.common.security.AdminApiGuard;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

	private final SettingsService settingsService;
	private final AdminApiGuard adminApiGuard;

	public SettingsController(SettingsService settingsService, AdminApiGuard adminApiGuard) {
		this.settingsService = settingsService;
		this.adminApiGuard = adminApiGuard;
	}

	@GetMapping
	public SettingsDtos.SettingsResponse getSettings(@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken) {
		adminApiGuard.require(adminToken);
		return settingsService.getSettings();
	}

	@PutMapping
	public SettingsDtos.SettingsResponse updateSettings(
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken,
			@Valid @RequestBody SettingsDtos.SettingsUpdateRequest request) {
		adminApiGuard.require(adminToken);
		return settingsService.updateSettings(request);
	}

	@PostMapping("/test-connections")
	public SettingsDtos.ConnectionTestResponse testConnections(@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken) {
		adminApiGuard.require(adminToken);
		return settingsService.testConnections();
	}
}
