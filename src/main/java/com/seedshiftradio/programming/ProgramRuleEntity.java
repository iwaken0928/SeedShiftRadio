package com.seedshiftradio.programming;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "program_rule")
public class ProgramRuleEntity {

	@Id
	private String id;

	@Column(name = "policy_id", nullable = false)
	private String policyId;

	@Column(nullable = false)
	private Integer priority;

	@Column(name = "days_of_week", nullable = false)
	private String daysOfWeek;

	@Column(name = "start_time", nullable = false)
	private String startTime;

	@Column(name = "end_time", nullable = false)
	private String endTime;

	@Column(name = "minimum_pending_letters", nullable = false)
	private Integer minimumPendingLetters;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "required_provider_states", nullable = false, columnDefinition = "jsonb")
	private List<String> requiredProviderStates = new ArrayList<>();

	@Column(name = "template_id", nullable = false)
	private String templateId;
}
