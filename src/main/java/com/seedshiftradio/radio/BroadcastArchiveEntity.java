package com.seedshiftradio.radio;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
@Table(name = "broadcast_archive")
public class BroadcastArchiveEntity {

	@Id
	private String id;

	@Column(name = "station_id", nullable = false)
	private String stationId;

	@Column(name = "source_play_history_id", nullable = false, unique = true)
	private String sourcePlayHistoryId;

	@Enumerated(EnumType.STRING)
	@Column(name = "segment_type", nullable = false)
	private SegmentType segmentType;

	@Column(nullable = false)
	private String title;

	@Column(name = "primary_asset_id", nullable = false)
	private String primaryAssetId;

	@Column(name = "script_asset_id")
	private String scriptAssetId;

	@Column(name = "archive_status", nullable = false)
	private String archiveStatus;

	@Column(name = "replay_weight", nullable = false)
	private Integer replayWeight;

	@Column(name = "replay_count", nullable = false)
	private Integer replayCount;

	@Column(name = "eligible_from", nullable = false)
	private Instant eligibleFrom;

	@Column(name = "last_replayed_at")
	private Instant lastReplayedAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> metadata = new LinkedHashMap<>();

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
		if (archiveStatus == null || archiveStatus.isBlank()) {
			archiveStatus = "ELIGIBLE";
		}
		if (replayWeight == null) {
			replayWeight = 1;
		}
		if (replayCount == null) {
			replayCount = 0;
		}
		if (eligibleFrom == null) {
			eligibleFrom = now;
		}
		if (metadata == null) {
			metadata = new LinkedHashMap<>();
		}
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
		if (archiveStatus == null || archiveStatus.isBlank()) {
			archiveStatus = "ELIGIBLE";
		}
		if (replayWeight == null) {
			replayWeight = 1;
		}
		if (replayCount == null) {
			replayCount = 0;
		}
		if (eligibleFrom == null) {
			eligibleFrom = updatedAt;
		}
		if (metadata == null) {
			metadata = new LinkedHashMap<>();
		}
	}
}
