package com.seedshiftradio.radio;

import java.time.Instant;

import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;

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
@Table(name = "queue_item")
public class QueueItemEntity {

	@Id
	private String id;

	@Column(name = "session_id", nullable = false)
	private String sessionId;

	@Column(name = "sequence_no", nullable = false)
	private Integer sequenceNo;

	@Enumerated(EnumType.STRING)
	@Column(name = "segment_type", nullable = false)
	private SegmentType segmentType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private QueueItemStatus status;

	@Column(name = "program_block_id")
	private String programBlockId;

	@Column(name = "program_slot_id")
	private String programSlotId;

	@Enumerated(EnumType.STRING)
	@Column(name = "slot_role", nullable = false)
	private SlotRole slotRole;

	@Column(nullable = false)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "playback_mode", nullable = false)
	private PlaybackMode playbackMode;

	@Column(name = "asset_url")
	private String assetUrl;

	@Column(name = "speech_directive_id")
	private String speechDirectiveId;

	@Column(name = "duration_ms", nullable = false)
	private Integer durationMs;

	@Column(name = "correlation_id", nullable = false)
	private String correlationId;

	@Column(name = "asset_banned", nullable = false)
	private boolean assetBanned;

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
