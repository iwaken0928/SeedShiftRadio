package com.seedshiftradio.monitor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seedshiftradio.common.security.AdminApiGuard;
import com.seedshiftradio.monitor.MonitorDtos.MonitorSummaryResponse;

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

	private final MonitorService monitorService;
	private final AdminApiGuard adminApiGuard;

	public MonitorController(MonitorService monitorService, AdminApiGuard adminApiGuard) {
		this.monitorService = monitorService;
		this.adminApiGuard = adminApiGuard;
	}

	@GetMapping("/summary")
	public MonitorSummaryResponse summary(@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken) {
		adminApiGuard.require(adminToken);
		return monitorService.summary();
	}
}
