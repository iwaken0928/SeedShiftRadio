package com.seedshiftradio.programming;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.seedshiftradio.domain.ConstraintMode;
import com.seedshiftradio.domain.SlotRole;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class ProgrammingDtos {

	private ProgrammingDtos() {
	}

	public record ProgramTemplateSummary(
			String id,
			String scope,
			String stationId,
			String name,
			Integer version,
			Integer targetDurationMinutes,
			Integer planningHorizonMinutes,
			boolean isActive,
			String fallbackTemplateId) {
	}

	public record ProgramTemplateDetail(
			String id,
			String scope,
			String stationId,
			String name,
			Integer version,
			Integer targetDurationMinutes,
			Integer planningHorizonMinutes,
			boolean isActive,
			Map<String, Object> editorialPolicy,
			String fallbackTemplateId,
			List<ProgramSlotDto> slots) {
	}

	public record ProgramTemplateRequest(
			@NotBlank String id,
			@NotBlank String scope,
			String stationId,
			@NotBlank String name,
			@NotNull Integer version,
			@NotNull Integer targetDurationMinutes,
			@NotNull Integer planningHorizonMinutes,
			boolean isActive,
			Map<String, Object> editorialPolicy,
			String fallbackTemplateId,
			@Valid @NotEmpty List<ProgramSlotDto> slots) {
	}

	public record ProgramSlotDto(
			@NotBlank String slotId,
			@NotNull SlotRole role,
			@NotNull ConstraintMode constraintMode,
			@NotEmpty List<@NotBlank String> candidateSegmentTypes,
			List<@NotBlank String> fallbackSegmentTypes,
			@NotNull Integer targetDurationMs,
			Map<String, Object> slotPolicy) {
	}

	public record ProgrammingPolicyResponse(
			String stationId,
			Integer version,
			boolean enabled,
			String defaultTemplateId,
			String fallbackStrategy,
			Integer planningHorizonMinutes,
			Instant updatedAt,
			List<ProgramRuleDto> rules) {
	}

	public record ProgramRuleDto(
			String id,
			Integer priority,
			List<String> days,
			String startTime,
			String endTime,
			Integer minimumPendingLetters,
			List<String> requiredProviderStates,
			String templateId) {
	}

	public record ProgrammingPolicyRequest(
			@NotNull Integer version,
			boolean enabled,
			String defaultTemplateId,
			@NotBlank String fallbackStrategy,
			@NotNull Integer planningHorizonMinutes,
			@Valid @NotNull List<ProgramRuleRequest> rules) {
	}

	public record ProgramRuleRequest(
			@NotNull Integer priority,
			@NotEmpty List<@NotBlank String> days,
			@NotBlank @Pattern(regexp = "^\\d{2}:\\d{2}$") String startTime,
			@NotBlank @Pattern(regexp = "^\\d{2}:\\d{2}$") String endTime,
			@NotNull Integer minimumPendingLetters,
			List<@NotBlank String> requiredProviderStates,
			@NotBlank String templateId) {
	}

	public record ProgrammingPreviewRequest(
			@NotNull OffsetDateTime at,
			@NotNull @Min(0) Integer pendingLetterCount,
			@NotNull Map<String, @NotBlank String> providerStates) {
	}

	public record ProgrammingPreviewResponse(
			String stationId,
			String selectedTemplateId,
			boolean fallbackApplied,
			PreviewProgram program,
			List<PreviewSlot> slots,
			List<ValidationWarning> validationWarnings) {
	}

	public record PreviewProgram(
			String title,
			Integer plannedDurationMs) {
	}

	public record PreviewSlot(
			String slotId,
			SlotRole role,
			ConstraintMode constraintMode,
			Integer targetDurationMs) {
	}

	public record ValidationWarning(
			String code,
			String message) {
	}
}
