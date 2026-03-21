package com.seedshiftradio.radio;

import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.seedshiftradio.domain.ConstraintMode;
import com.seedshiftradio.domain.ProgramBlockSlotStatus;
import com.seedshiftradio.domain.SegmentType;
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
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "program_block_slot")
public class ProgramBlockSlotEntity {

	@Id
	private String id;

	@Column(name = "program_block_id", nullable = false)
	private String programBlockId;

	@Column(name = "template_slot_id")
	private String templateSlotId;

	@Column(name = "sequence_no", nullable = false)
	private Integer sequenceNo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SlotRole role;

	@Enumerated(EnumType.STRING)
	@Column(name = "constraint_mode", nullable = false)
	private ConstraintMode constraintMode;

	@Enumerated(EnumType.STRING)
	@Column(name = "resolved_segment_type", nullable = false)
	private SegmentType resolvedSegmentType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ProgramBlockSlotStatus status;

	@Column(name = "target_duration_ms", nullable = false)
	private Integer targetDurationMs;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "slot_context", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> slotContext = new LinkedHashMap<>();
}
