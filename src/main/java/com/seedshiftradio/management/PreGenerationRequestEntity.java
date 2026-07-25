package com.seedshiftradio.management;

import java.time.Instant;

import com.seedshiftradio.domain.PreGenerationRequestStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "pre_generation_request")
public class PreGenerationRequestEntity {

	@Id
	private String id;

	@Column(name = "station_id", nullable = false)
	private String stationId;

	@Column(name = "session_id", nullable = false)
	private String sessionId;

	@Column(name = "program_template_id")
	private String programTemplateId;

	@Column(name = "target_program_count", nullable = false)
	private Integer targetProgramCount;

	@Column(name = "include_speech", nullable = false)
	private boolean includeSpeech;

	@Column(name = "include_music", nullable = false)
	private boolean includeMusic;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PreGenerationRequestStatus status;

	@Column(name = "materialized_program_count", nullable = false)
	private Integer materializedProgramCount;

	@Column(name = "materialized_segment_count", nullable = false)
	private Integer materializedSegmentCount;

	@Column(name = "queued_music_count", nullable = false)
	private Integer queuedMusicCount;

	@Column(name = "error_code")
	private String errorCode;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		requestedAt = requestedAt == null ? now : requestedAt;
		updatedAt = now;
		materializedProgramCount = materializedProgramCount == null ? 0 : materializedProgramCount;
		materializedSegmentCount = materializedSegmentCount == null ? 0 : materializedSegmentCount;
		queuedMusicCount = queuedMusicCount == null ? 0 : queuedMusicCount;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
