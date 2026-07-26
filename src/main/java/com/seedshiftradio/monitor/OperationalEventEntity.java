package com.seedshiftradio.monitor;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "operational_event_log")
public class OperationalEventEntity {

	@Id
	private String id;

	@Column(nullable = false)
	private String level;

	@Column(nullable = false)
	private String category;

	@Column(name = "event_type", nullable = false)
	private String eventType;

	@Column(name = "source_id")
	private String sourceId;

	@Column(name = "correlation_id")
	private String correlationId;

	@Column(name = "provider_type")
	private String providerType;

	@Column(name = "provider_key")
	private String providerKey;

	@Column(name = "error_code")
	private String errorCode;

	@Column(nullable = false, length = 500)
	private String message;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@PrePersist
	void onCreate() {
		occurredAt = occurredAt == null ? Instant.now() : occurredAt;
	}
}
