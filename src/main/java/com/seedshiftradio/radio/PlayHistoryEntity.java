package com.seedshiftradio.radio;

import java.time.Instant;

import com.seedshiftradio.domain.PlayHistoryResultStatus;
import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.domain.SegmentType;

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
@Table(name = "play_history")
public class PlayHistoryEntity {

	@Id
	private String id;

	@Column(name = "session_id", nullable = false)
	private String sessionId;

	@Column(name = "station_id", nullable = false)
	private String stationId;

	@Column(name = "queue_item_id", nullable = false)
	private String queueItemId;

	@Column(name = "letter_id")
	private String letterId;

	@Column(name = "program_block_id")
	private String programBlockId;

	@Column(name = "program_slot_id")
	private String programSlotId;

	@Enumerated(EnumType.STRING)
	@Column(name = "segment_type", nullable = false)
	private SegmentType segmentType;

	@Column(nullable = false)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "playback_mode", nullable = false)
	private PlaybackMode playbackMode;

	@Enumerated(EnumType.STRING)
	@Column(name = "result_status", nullable = false)
	private PlayHistoryResultStatus resultStatus;

	@Column(name = "correlation_id", nullable = false)
	private String correlationId;

	@Column(name = "content_origin", nullable = false)
	private String contentOrigin;

	@Column(name = "replay_of_play_history_id")
	private String replayOfPlayHistoryId;

	@Column(name = "played_at", nullable = false)
	private Instant playedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		if (playedAt == null) {
			playedAt = now;
		}
		if (contentOrigin == null || contentOrigin.isBlank()) {
			contentOrigin = "LIVE_GEN";
		}
		createdAt = now;
	}

	@PreUpdate
	void onUpdate() {
		if (playedAt == null) {
			playedAt = Instant.now();
		}
		if (contentOrigin == null || contentOrigin.isBlank()) {
			contentOrigin = "LIVE_GEN";
		}
	}
}
