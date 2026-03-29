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
		if (metadata == null) {
			metadata = new LinkedHashMap<>();
		}
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
		if (metadata == null) {
			metadata = new LinkedHashMap<>();
		}
	}
}
