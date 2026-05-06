package com.seedshiftradio.radio;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.seedshiftradio.common.security.AdminApiGuard;
import com.seedshiftradio.domain.PlayHistoryResultStatus;
import com.seedshiftradio.radio.PlayHistoryDtos.PlayHistoryResponse;

@RestController
@RequestMapping("/api/play-history")
public class PlayHistoryController {

	private final PlayHistoryQueryService playHistoryQueryService;
	private final AdminApiGuard adminApiGuard;

	public PlayHistoryController(PlayHistoryQueryService playHistoryQueryService, AdminApiGuard adminApiGuard) {
		this.playHistoryQueryService = playHistoryQueryService;
		this.adminApiGuard = adminApiGuard;
	}

	@GetMapping
	public List<PlayHistoryResponse> list(
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken,
			@RequestParam(required = false) String sessionId,
			@RequestParam(required = false) String stationId,
			@RequestParam(required = false) String letterId,
			@RequestParam(required = false) PlayHistoryResultStatus resultStatus,
			@RequestParam(required = false) Integer limit) {
		adminApiGuard.require(adminToken);
		return playHistoryQueryService.list(sessionId, stationId, letterId, resultStatus, limit);
	}

	@GetMapping("/{id}")
	public PlayHistoryResponse get(
			@PathVariable("id") String playHistoryId,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken) {
		adminApiGuard.require(adminToken);
		return playHistoryQueryService.get(playHistoryId);
	}
}
