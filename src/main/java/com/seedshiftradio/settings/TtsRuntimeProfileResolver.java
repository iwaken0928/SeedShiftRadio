package com.seedshiftradio.settings;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.radio.PlayoutSessionRepository;
import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.station.StationRepository;
import com.seedshiftradio.station.VoiceProfileEntity;
import com.seedshiftradio.station.VoiceProfileRepository;

@Service
public class TtsRuntimeProfileResolver {

	private static final BigDecimal MIN_SPEED = new BigDecimal("0.25");
	private static final BigDecimal MAX_SPEED = new BigDecimal("4.0");

	private final PlayoutSessionRepository playoutSessionRepository;
	private final StationRepository stationRepository;
	private final VoiceProfileRepository voiceProfileRepository;

	public TtsRuntimeProfileResolver(
			PlayoutSessionRepository playoutSessionRepository,
			StationRepository stationRepository,
			VoiceProfileRepository voiceProfileRepository) {
		this.playoutSessionRepository = playoutSessionRepository;
		this.stationRepository = stationRepository;
		this.voiceProfileRepository = voiceProfileRepository;
	}

	public Optional<TtsRuntimeProfile> resolve(QueueItemEntity item) {
		if (item == null || item.getSessionId() == null || item.getSessionId().isBlank()) {
			return Optional.empty();
		}
		var session = playoutSessionRepository.findById(item.getSessionId())
				.orElseThrow(() -> unavailableProfile("TTS 実行対象の再生セッションが見つかりません。"));
		var station = stationRepository.findById(session.getStationId())
				.orElseThrow(() -> unavailableProfile("TTS 実行対象の局が見つかりません。"));
		if (station.getDefaultVoiceProfileId() == null || station.getDefaultVoiceProfileId().isBlank()) {
			throw unavailableProfile("TTS 実行対象の音声プロファイルが設定されていません。");
		}
		VoiceProfileEntity profile = voiceProfileRepository.findById(station.getDefaultVoiceProfileId())
				.orElseThrow(() -> unavailableProfile("TTS 実行対象の音声プロファイルが見つかりません。"));
		return Optional.of(toRuntimeProfile(station.getId(), profile));
	}

	private TtsSynthesisException unavailableProfile(String message) {
		return new TtsSynthesisException(ProviderErrorCode.PROVIDER_REJECTED, message);
	}

	private TtsRuntimeProfile toRuntimeProfile(String stationId, VoiceProfileEntity profile) {
		validateScope(stationId, profile);
		validateSpeed(profile);
		validateReferenceVoice(profile);
		return new TtsRuntimeProfile(
				profile.getId(),
				stationId,
				profile.getProviderKey(),
				profile.getEngineType(),
				profile.getSpeakerKey(),
				profile.getStyleKey(),
				profile.getSpeed(),
				profile.getProviderOptions(),
				profile.getReferenceVoiceRef(),
				profile.getConsentPolicyRef());
	}

	private void validateScope(String stationId, VoiceProfileEntity profile) {
		boolean global = "GLOBAL".equalsIgnoreCase(profile.getScope()) && profile.getStationId() == null;
		boolean stationScoped = "STATION".equalsIgnoreCase(profile.getScope())
				&& stationId.equals(profile.getStationId());
		if (!global && !stationScoped) {
			throw new TtsSynthesisException(
					ProviderErrorCode.PROVIDER_REJECTED,
					"TTS 音声プロファイルの局スコープが一致しません。");
		}
	}

	private void validateSpeed(VoiceProfileEntity profile) {
		if (profile.getSpeed() == null
				|| profile.getSpeed().compareTo(MIN_SPEED) < 0
				|| profile.getSpeed().compareTo(MAX_SPEED) > 0) {
			throw new TtsSynthesisException(
					ProviderErrorCode.PROVIDER_REJECTED,
					"TTS 音声プロファイルの速度が許容範囲外です。");
		}
	}

	private void validateReferenceVoice(VoiceProfileEntity profile) {
		String reference = profile.getReferenceVoiceRef();
		if (reference == null) {
			return;
		}
		if (profile.getConsentPolicyRef() == null || profile.getConsentPolicyRef().isBlank()) {
			throw new TtsSynthesisException(
					ProviderErrorCode.VOICE_CONSENT_REQUIRED,
					"TTS 参照音声の同意情報を確認できません。");
		}
		if (!isSafeReference(reference)) {
			throw new TtsSynthesisException(
					ProviderErrorCode.VOICE_REF_NOT_FOUND,
					"TTS 参照音声を安全に解決できません。");
		}
	}

	private boolean isSafeReference(String reference) {
		String normalized = reference.trim();
		if (normalized.isBlank()
				|| !normalized.equals(reference)
				|| normalized.startsWith("/")
				|| normalized.startsWith("\\")
				|| normalized.matches("^[A-Za-z]:[\\\\/].*")
				|| normalized.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
			return false;
		}
		for (String segment : normalized.split("[\\\\/]")) {
			if ("..".equals(segment)) {
				return false;
			}
		}
		return true;
	}
}
