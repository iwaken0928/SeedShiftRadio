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

	public static final String PURPOSE_LIVE = "LIVE";
	public static final String PURPOSE_PRE_GENERATION = "PRE_GENERATION";

	@Id
	private String id;

	@Column(name = "station_id", nullable = false)
	private String stationId;

	@Column(name = "requested_by")
	private String requestedBy;

	@Column(name = "resume_playback", nullable = false)
	private boolean resumePlayback;

	@Column(nullable = false)
	private String purpose;

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

	public static PlayoutSessionEntity preGeneration(String id, String stationId, String correlationId) {
		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setId(id);
		session.setStationId(stationId);
		session.setRequestedBy("ADMIN_PRE_GENERATION");
		session.setResumePlayback(false);
		session.setPurpose(PURPOSE_PRE_GENERATION);
		session.setState(PlayoutState.PREPARING);
		session.setBufferReadyCount(0);
		session.setCorrelationId(correlationId);
		return session;
	}

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		if (bufferReadyCount == null) {
			bufferReadyCount = 0;
		}
		if (purpose == null || purpose.isBlank()) {
			purpose = PURPOSE_LIVE;
		}
		startedAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		if (bufferReadyCount == null) {
			bufferReadyCount = 0;
		}
		if (purpose == null || purpose.isBlank()) {
			purpose = PURPOSE_LIVE;
		}
		updatedAt = Instant.now();
	}

	public boolean isPreGeneration() {
		return PURPOSE_PRE_GENERATION.equals(purpose);
	}
}
