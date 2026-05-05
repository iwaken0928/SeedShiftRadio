package com.seedshiftradio.programming;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.seedshiftradio.domain.ConstraintMode;
import com.seedshiftradio.domain.SlotRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@Entity
@Table(name = "program_template_slot")
public class ProgramTemplateSlotEntity {

	@Id
	private String id;

	@Column(name = "program_template_id", nullable = false)
	private String programTemplateId;

	@Column(name = "sequence_no", nullable = false)
	private Integer sequenceNo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SlotRole role;

	@Enumerated(EnumType.STRING)
	@Column(name = "constraint_mode", nullable = false)
	private ConstraintMode constraintMode;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "candidate_segment_types", nullable = false, columnDefinition = "jsonb")
	private List<String> candidateSegmentTypes = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "fallback_segment_types", nullable = false, columnDefinition = "jsonb")
	private List<String> fallbackSegmentTypes = new ArrayList<>();

	@Column(name = "target_duration_ms", nullable = false)
	private Integer targetDurationMs;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "slot_policy", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> slotPolicy = new LinkedHashMap<>();
}
