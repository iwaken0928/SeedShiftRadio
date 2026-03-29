package com.seedshiftradio.letter;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.seedshiftradio.common.security.AdminApiGuard;
import com.seedshiftradio.domain.LetterStatus;
import com.seedshiftradio.letter.LetterDtos.LetterCreateRequest;
import com.seedshiftradio.letter.LetterDtos.LetterCreateResponse;
import com.seedshiftradio.letter.LetterDtos.LetterDetailResponse;
import com.seedshiftradio.letter.LetterDtos.LetterReplyRequest;
import com.seedshiftradio.letter.LetterDtos.LetterReplyResponse;
import com.seedshiftradio.letter.LetterDtos.LetterStatusUpdateRequest;
import com.seedshiftradio.letter.LetterDtos.LetterSummaryResponse;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/letters")
public class LetterController {

	private final LetterService letterService;
	private final AdminApiGuard adminApiGuard;

	public LetterController(LetterService letterService, AdminApiGuard adminApiGuard) {
		this.letterService = letterService;
		this.adminApiGuard = adminApiGuard;
	}

	@GetMapping
	public List<LetterSummaryResponse> list(
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken,
			@RequestParam(required = false) String stationId,
			@RequestParam(required = false) LetterStatus status) {
		adminApiGuard.require(adminToken);
		return letterService.list(stationId, status);
	}

	@PostMapping
	public LetterCreateResponse create(
			@Valid @RequestBody LetterCreateRequest request,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		return letterService.create(request, idempotencyKey);
	}

	@GetMapping("/{id}")
	public LetterDetailResponse get(
			@PathVariable("id") String letterId,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken) {
		adminApiGuard.require(adminToken);
		return letterService.get(letterId);
	}

	@PostMapping("/{id}/status")
	public LetterSummaryResponse updateStatus(
			@PathVariable("id") String letterId,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken,
			@Valid @RequestBody LetterStatusUpdateRequest request) {
		adminApiGuard.require(adminToken);
		return letterService.updateStatus(letterId, request);
	}

	@PostMapping("/{id}/reply")
	public LetterReplyResponse addReply(
			@PathVariable("id") String letterId,
			@RequestHeader(value = AdminApiGuard.HEADER_NAME, required = false) String adminToken,
			@Valid @RequestBody LetterReplyRequest request) {
		adminApiGuard.require(adminToken);
		return letterService.addReply(letterId, request);
	}
}
