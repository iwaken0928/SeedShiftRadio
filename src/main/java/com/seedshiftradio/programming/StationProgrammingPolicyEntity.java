package com.seedshiftradio.programming;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "station_programming_policy")
public class StationProgrammingPolicyEntity {

	@Id
	private String id;

	@Column(name = "station_id", nullable = false, unique = true)
	private String stationId;

	@Version
	@Column(nullable = false)
	private Integer version;

	@Column(name = "default_template_id")
	private String defaultTemplateId;

	@Column(name = "fallback_strategy", nullable = false)
	private String fallbackStrategy;

	@Column(name = "planning_horizon_minutes", nullable = false)
	private Integer planningHorizonMinutes;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		updatedAt = Instant.now();
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
