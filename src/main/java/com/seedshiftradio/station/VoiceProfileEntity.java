package com.seedshiftradio.station;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.seedshiftradio.domain.PlaybackMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "voice_profile")
public class VoiceProfileEntity {

	@Id
	private String id;

	@Column(name = "engine_type", nullable = false)
	private String engineType;

	@Column(nullable = false, length = 40)
	private String scope;

	@Column(name = "station_id")
	private String stationId;

	@Column(name = "provider_key")
	private String providerKey;

	@Column(name = "speaker_key", nullable = false)
	private String speakerKey;

	@Column(name = "style_key")
	private String styleKey;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "provider_options", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> providerOptions = new LinkedHashMap<>();

	@Column(name = "reference_voice_ref")
	private String referenceVoiceRef;

	@Column(name = "consent_policy_ref")
	private String consentPolicyRef;

	@Column(nullable = false, precision = 4, scale = 2)
	private BigDecimal speed;

	@Column(nullable = false, precision = 4, scale = 2)
	private BigDecimal pitch;

	@Enumerated(EnumType.STRING)
	@Column(name = "playback_mode", nullable = false)
	private PlaybackMode playbackMode;
}
