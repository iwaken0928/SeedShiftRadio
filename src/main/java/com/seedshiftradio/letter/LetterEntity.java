package com.seedshiftradio.letter;

import java.time.Instant;

import com.seedshiftradio.domain.LetterStatus;

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
@Table(name = "letter")
public class LetterEntity {

	@Id
	private String id;

	@Column(name = "station_id")
	private String stationId;

	@Column(name = "radio_name", nullable = false)
	private String radioName;

	@Column(nullable = false)
	private String subject;

	@Column(nullable = false)
	private String body;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private LetterStatus status;

	@Column(name = "adopted_in_session_id")
	private String adoptedInSessionId;

	@Column(name = "idempotency_key")
	private String idempotencyKey;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public LetterEntity(
			String id,
			String stationId,
			String radioName,
			String subject,
			String body,
			LetterStatus status,
			String idempotencyKey) {
		this.id = id;
		this.stationId = stationId;
		this.radioName = radioName;
		this.subject = subject;
		this.body = body;
		this.status = status;
		this.idempotencyKey = idempotencyKey;
	}

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
