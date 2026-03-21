package com.seedshiftradio.radio;

import java.time.Instant;

import com.seedshiftradio.domain.ProgramBlockStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "program_block")
public class ProgramBlockEntity {

	@Id
	private String id;

	@Column(name = "station_id", nullable = false)
	private String stationId;

	@Column(name = "session_id", nullable = false)
	private String sessionId;

	@Column(name = "program_template_id")
	private String programTemplateId;

	@Column(name = "program_template_version")
	private Integer programTemplateVersion;

	@Column(nullable = false)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ProgramBlockStatus status;

	@Column(name = "planned_duration_ms", nullable = false)
	private Integer plannedDurationMs;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@PrePersist
	void onCreate() {
		if (startedAt == null) {
			startedAt = Instant.now();
		}
	}
}
