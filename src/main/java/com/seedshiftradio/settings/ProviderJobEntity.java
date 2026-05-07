package com.seedshiftradio.settings;

import java.time.Instant;

import com.seedshiftradio.domain.ProviderJobStatus;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;

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
@Table(name = "provider_job")
public class ProviderJobEntity {

	@Id
	private String id;

	@Enumerated(EnumType.STRING)
	@Column(name = "job_type", nullable = false)
	private ProviderJobType jobType;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider_type", nullable = false)
	private ProviderType providerType;

	@Column(name = "provider_key")
	private String providerKey;

	@Column(name = "queue_item_id")
	private String queueItemId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ProviderJobStatus status;

	@Column(name = "correlation_id", nullable = false)
	private String correlationId;

	@Column(name = "external_ref")
	private String externalRef;

	@Column(name = "error_code")
	private String errorCode;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

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
