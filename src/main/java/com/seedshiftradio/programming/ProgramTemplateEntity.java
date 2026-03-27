package com.seedshiftradio.programming;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "program_template")
public class ProgramTemplateEntity {

	@Id
	private String id;

	@Column(nullable = false)
	private String scope;

	@Column(name = "station_id")
	private String stationId;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private Integer version;

	@Column(name = "target_duration_minutes", nullable = false)
	private Integer targetDurationMinutes;

	@Column(name = "planning_horizon_minutes", nullable = false)
	private Integer planningHorizonMinutes;

	@Column(name = "is_active", nullable = false)
	private boolean active;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "editorial_policy", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> editorialPolicy = new LinkedHashMap<>();

	@Column(name = "fallback_template_id")
	private String fallbackTemplateId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
