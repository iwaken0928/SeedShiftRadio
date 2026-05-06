package com.seedshiftradio.radio;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.seedshiftradio.domain.PlaybackMode;

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
@Table(name = "client_capabilities")
public class ClientCapabilitiesEntity {

	@Id
	@Column(name = "client_id", nullable = false, length = 100)
	private String clientId;

	@Column(name = "client_type", nullable = false, length = 100)
	private String clientType;

	@Column(name = "supports_client_side_tts", nullable = false)
	private boolean supportsClientSideTts;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "supported_voice_engines", nullable = false, columnDefinition = "jsonb")
	private List<String> supportedVoiceEngines = new ArrayList<>();

	@Enumerated(EnumType.STRING)
	@Column(name = "preferred_playback_mode", nullable = false, length = 40)
	private PlaybackMode preferredPlaybackMode;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "local_voice_profiles", nullable = false, columnDefinition = "jsonb")
	private List<StoredLocalVoiceProfile> localVoiceProfiles = new ArrayList<>();

	@Column(name = "accepted_at", nullable = false)
	private Instant acceptedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		if (acceptedAt == null) {
			acceptedAt = now;
		}
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}

	@Getter
	@Setter
	@NoArgsConstructor
	public static class StoredLocalVoiceProfile {

		private String engine;
		private String profileKey;

		public StoredLocalVoiceProfile(String engine, String profileKey) {
			this.engine = engine;
			this.profileKey = profileKey;
		}
	}
}
