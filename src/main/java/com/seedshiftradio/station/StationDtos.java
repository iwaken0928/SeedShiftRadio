package com.seedshiftradio.station;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.seedshiftradio.domain.ConstraintMode;
import com.seedshiftradio.domain.SlotRole;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class StationDtos {

	private StationDtos() {
	}

	public record StationSummary(
			String id,
			String name,
			BigDecimal frequencyMHz,
			String genre,
			boolean isActive,
			boolean programmingEnabled,
			String defaultProgramTemplateId) {
	}

	public record Programming(
			boolean enabled,
			String defaultTemplateId,
			String fallbackStrategy,
			Integer planningHorizonMinutes,
			Integer version) {
	}

	public record StationDetail(
			String id,
			String name,
			BigDecimal frequencyMHz,
			String genre,
			String languagePersonaId,
			String defaultVoiceProfileId,
			boolean isActive,
			Programming programming) {
	}

	public record StationResponse(
			String id,
			String name,
			BigDecimal frequencyMHz,
			String genre,
			String languagePersonaId,
			String defaultVoiceProfileId,
			boolean isActive,
			boolean programmingEnabled,
			String defaultProgramTemplateId,
			Integer version,
			Instant updatedAt) {
	}

	public record StationSummaryResponse(
			String id,
			String name,
			BigDecimal frequencyMHz,
			String genre,
			boolean isActive,
			boolean programmingEnabled,
			String defaultProgramTemplateId) {
	}

	public record StationProgrammingSummary(
			boolean enabled,
			String defaultTemplateId,
			String fallbackStrategy,
			Integer planningHorizonMinutes) {
	}

	public record StationDetailResponse(
			String id,
			String name,
			BigDecimal frequencyMHz,
			String genre,
			String languagePersonaId,
			String defaultVoiceProfileId,
			boolean isActive,
			Integer version,
			StationProgrammingSummary programming) {
	}

	public record StationUpsertRequest(
			Integer version,
			@NotBlank @Size(max = 100) String id,
			@NotBlank @Size(max = 255) String name,
			@NotNull @DecimalMin("0.1") BigDecimal frequencyMHz,
			@NotBlank @Size(max = 100) String genre,
			@NotBlank @Size(max = 100) String languagePersonaId,
			@NotBlank @Size(max = 100) String defaultVoiceProfileId,
			boolean isActive,
			boolean programmingEnabled,
			String defaultProgramTemplateId) {
	}

	public record ProgrammingRuleRequest(
			@NotNull Integer priority,
			@NotEmpty List<@NotBlank String> days,
			@NotBlank String startTime,
			@NotBlank String endTime,
			@NotNull Integer minimumPendingLetters,
			List<@NotBlank String> requiredProviderStates,
			@NotBlank String templateId) {
	}

	public record ProgrammingPolicyResponse(
			String stationId,
			Integer version,
			boolean enabled,
			String defaultTemplateId,
			String fallbackStrategy,
			Integer planningHorizonMinutes,
			Instant updatedAt,
			List<ProgrammingRuleResponse> rules) {
	}

	public record ProgrammingRuleResponse(
			String id,
			Integer priority,
			List<String> days,
			String startTime,
			String endTime,
			Integer minimumPendingLetters,
			List<String> requiredProviderStates,
			String templateId) {
	}

	public record ProgrammingPolicyUpdateRequest(
			@NotNull Integer version,
			boolean enabled,
			String defaultTemplateId,
			@NotBlank String fallbackStrategy,
			@NotNull Integer planningHorizonMinutes,
			@Valid List<ProgrammingRuleRequest> rules) {
	}

	public record ProgrammingPolicyUpdateResponse(String stationId, Integer version, boolean enabled, Instant updatedAt) {
	}

	public record ProviderStatesRequest(String musicGen, String tts, String llm) {
	}

	public record ProgrammingPreviewRequest(
			@NotNull OffsetDateTime at,
			@NotNull Integer pendingLetterCount,
			ProviderStatesRequest providerStates) {
	}

	public record ProgrammingPreviewResponse(
			String stationId,
			String selectedTemplateId,
			boolean fallbackApplied,
			PreviewProgram program,
			List<PreviewSlot> slots,
			List<String> validationWarnings) {
	}

	public record PreviewProgram(String title, Integer plannedDurationMs) {
	}

	public record PreviewSlot(
			String slotId,
			SlotRole role,
			ConstraintMode constraintMode,
			Integer targetDurationMs,
			String resolvedSegmentType) {
	}

	public record ProgramTemplateSummaryResponse(
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

	public record ProgramTemplateDetailResponse(
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
			List<ProgramTemplateSlotResponse> slots) {
	}

	public record ProgramTemplateSlotResponse(
			String id,
			Integer sequenceNo,
			SlotRole role,
			ConstraintMode constraintMode,
			List<String> candidateSegmentTypes,
			List<String> fallbackSegmentTypes,
			Integer targetDurationMs,
			Map<String, Object> slotPolicy) {
	}

	public record ProgramTemplateUpsertRequest(
			Integer version,
			@NotBlank @Size(max = 100) String id,
			@NotBlank String scope,
			String stationId,
			@NotBlank @Size(max = 255) String name,
			@NotNull Integer targetDurationMinutes,
			@NotNull Integer planningHorizonMinutes,
			boolean isActive,
			Map<String, Object> editorialPolicy,
			String fallbackTemplateId,
			@NotEmpty @Valid List<ProgramTemplateSlotRequest> slots) {
	}

	public record ProgramTemplateSlotRequest(
			String id,
			@NotNull Integer sequenceNo,
			@NotNull SlotRole role,
			@NotNull ConstraintMode constraintMode,
			@NotEmpty List<@NotBlank String> candidateSegmentTypes,
			List<@NotBlank String> fallbackSegmentTypes,
			@NotNull Integer targetDurationMs,
			Map<String, Object> slotPolicy) {
	}
}
