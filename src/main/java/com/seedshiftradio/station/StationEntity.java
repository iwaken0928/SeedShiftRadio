package com.seedshiftradio.station;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "station", uniqueConstraints = @UniqueConstraint(name = "uq_station_frequency_mhz", columnNames = "frequency_mhz"))
public class StationEntity {

	@Id
	private String id;

	@Column(nullable = false)
	private String name;

	@Column(name = "frequency_mhz", nullable = false, precision = 4, scale = 1)
	private BigDecimal frequencyMhz;

	@Column(nullable = false)
	private String genre;

	@Column(name = "language_persona_id", nullable = false)
	private String languagePersonaId;

	@Column(name = "default_voice_profile_id", nullable = false)
	private String defaultVoiceProfileId;

	@Column(name = "programming_enabled", nullable = false)
	private boolean programmingEnabled;

	@Column(name = "default_program_template_id")
	private String defaultProgramTemplateId;

	@Column(name = "is_active", nullable = false)
	private boolean active;

	@Version
	@Column(nullable = false)
	private Integer version;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public StationEntity(
			String id,
			String name,
			BigDecimal frequencyMhz,
			String genre,
			String languagePersonaId,
			String defaultVoiceProfileId,
			boolean programmingEnabled,
			String defaultProgramTemplateId,
			boolean active) {
		this.id = id;
		this.name = name;
		this.frequencyMhz = frequencyMhz;
		this.genre = genre;
		this.languagePersonaId = languagePersonaId;
		this.defaultVoiceProfileId = defaultVoiceProfileId;
		this.programmingEnabled = programmingEnabled;
		this.defaultProgramTemplateId = defaultProgramTemplateId;
		this.active = active;
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
