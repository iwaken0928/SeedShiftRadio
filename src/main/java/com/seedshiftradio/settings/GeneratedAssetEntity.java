package com.seedshiftradio.settings;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.seedshiftradio.domain.GeneratedAssetType;

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
@Table(name = "generated_asset")
public class GeneratedAssetEntity {

	@Id
	private String id;

	@Enumerated(EnumType.STRING)
	@Column(name = "asset_type", nullable = false)
	private GeneratedAssetType assetType;

	@Column(name = "storage_path", nullable = false)
	private String storagePath;

	@Column(name = "content_hash", nullable = false)
	private String contentHash;

	@Column(name = "provider_fingerprint", nullable = false)
	private String providerFingerprint;

	@Column(name = "cache_key")
	private String cacheKey;

	@Column(name = "byte_size", nullable = false)
	private Long byteSize;

	@Column(name = "reuse_scope", nullable = false)
	private String reuseScope;

	@Column(name = "reuse_count", nullable = false)
	private Integer reuseCount;

	@Column(name = "last_accessed_at", nullable = false)
	private Instant lastAccessedAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "archive_eligible", nullable = false)
	private boolean archiveEligible;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> metadata = new LinkedHashMap<>();

	@Column(name = "queue_item_id")
	private String queueItemId;

	@Column(name = "provider_job_id")
	private String providerJobId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
		if (byteSize == null) {
			byteSize = 0L;
		}
		if (reuseScope == null || reuseScope.isBlank()) {
			reuseScope = "STATION";
		}
		if (reuseCount == null) {
			reuseCount = 0;
		}
		if (lastAccessedAt == null) {
			lastAccessedAt = now;
		}
		if (metadata == null) {
			metadata = new LinkedHashMap<>();
		}
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
		if (byteSize == null) {
			byteSize = 0L;
		}
		if (reuseScope == null || reuseScope.isBlank()) {
			reuseScope = "STATION";
		}
		if (reuseCount == null) {
			reuseCount = 0;
		}
		if (lastAccessedAt == null) {
			lastAccessedAt = updatedAt;
		}
		if (metadata == null) {
			metadata = new LinkedHashMap<>();
		}
	}
}
