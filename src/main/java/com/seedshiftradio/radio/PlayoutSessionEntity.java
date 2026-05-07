package com.seedshiftradio.radio;

import java.time.Instant;

import com.seedshiftradio.domain.PlayoutState;

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
@Table(name = "playout_session")
public class PlayoutSessionEntity {

	@Id
	private String id;

	@Column(name = "station_id", nullable = false)
	private String stationId;

	@Column(name = "requested_by")
	private String requestedBy;

	@Column(name = "resume_playback", nullable = false)
	private boolean resumePlayback;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PlayoutState state;

	@Column(name = "current_queue_item_id")
	private String currentQueueItemId;

	@Column(name = "current_program_block_id")
	private String currentProgramBlockId;

	@Column(name = "buffer_ready_count", nullable = false)
	private Integer bufferReadyCount;

	@Column(name = "degraded_reason")
	private String degradedReason;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "correlation_id", nullable = false)
	private String correlationId;

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		if (bufferReadyCount == null) {
			bufferReadyCount = 0;
		}
		startedAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		if (bufferReadyCount == null) {
			bufferReadyCount = 0;
		}
		updatedAt = Instant.now();
	}
}
