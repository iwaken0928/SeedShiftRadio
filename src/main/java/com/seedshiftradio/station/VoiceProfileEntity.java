package com.seedshiftradio.station;

import java.math.BigDecimal;

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

	@Column(name = "speaker_key", nullable = false)
	private String speakerKey;

	@Column(name = "style_key")
	private String styleKey;

	@Column(nullable = false, precision = 4, scale = 2)
	private BigDecimal speed;

	@Column(nullable = false, precision = 4, scale = 2)
	private BigDecimal pitch;

	@Enumerated(EnumType.STRING)
	@Column(name = "playback_mode", nullable = false)
	private PlaybackMode playbackMode;
}
