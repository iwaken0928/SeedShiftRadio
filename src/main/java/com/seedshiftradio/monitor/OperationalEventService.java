package com.seedshiftradio.monitor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.settings.ProviderJobEntity;

@Service
public class OperationalEventService {

	private static final int MAX_MESSAGE_LENGTH = 500;
	private static final Duration RETENTION = Duration.ofDays(30);

	private final OperationalEventRepository operationalEventRepository;

	public OperationalEventService(OperationalEventRepository operationalEventRepository) {
		this.operationalEventRepository = operationalEventRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordProviderJob(String eventType, ProviderJobEntity job) {
		String errorCode = blankToNull(job.getErrorCode());
		save(
				errorCode == null ? "INFO" : "ERROR",
				"PROVIDER_JOB",
				eventType,
				job.getId(),
				job.getCorrelationId(),
				job.getProviderType() == null ? null : job.getProviderType().name(),
				job.getProviderKey(),
				errorCode,
				providerJobMessage(job, errorCode));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordPreGenerationFailure(
			String requestId,
			String correlationId,
			String errorCode,
			String message) {
		save(
				"ERROR",
				"PRE_GENERATION",
				"pre_generation.failed",
				requestId,
				correlationId,
				null,
				null,
				errorCode,
				message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordStationContentDeletion(
			String stationId,
			int deletedAssetCount,
			int failedAssetCount,
			long reclaimedBytes) {
		save(
				failedAssetCount == 0 ? "INFO" : "WARN",
				"CONTENT_MANAGEMENT",
				"station_content.deleted",
				stationId,
				null,
				null,
				null,
				failedAssetCount == 0 ? null : "CONTENT_DELETE_PARTIAL_FAILURE",
				"局別の事前生成コンテンツを削除しました。"
						+ " deletedAssetCount=" + deletedAssetCount
						+ ", failedAssetCount=" + failedAssetCount
						+ ", reclaimedBytes=" + reclaimedBytes);
	}

	@Transactional(readOnly = true)
	public List<MonitorDtos.OperationalEventSummary> recent(int limit) {
		int effectiveLimit = Math.max(1, Math.min(200, limit));
		return operationalEventRepository.findAllByOrderByOccurredAtDesc(PageRequest.of(0, effectiveLimit)).stream()
				.map(OperationalEventService::toSummary)
				.toList();
	}

	private void save(
			String level,
			String category,
			String eventType,
			String sourceId,
			String correlationId,
			String providerType,
			String providerKey,
			String errorCode,
			String message) {
		OperationalEventEntity entity = new OperationalEventEntity();
		entity.setId("oplog-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
		entity.setLevel(normalizeToken(level, "INFO"));
		entity.setCategory(normalizeToken(category, "SYSTEM"));
		entity.setEventType(trimToLength(eventType, 100));
		entity.setSourceId(trimToLength(sourceId, 100));
		entity.setCorrelationId(trimToLength(correlationId, 100));
		entity.setProviderType(trimToLength(providerType, 40));
		entity.setProviderKey(trimToLength(providerKey, 100));
		entity.setErrorCode(trimToLength(errorCode, 100));
		entity.setMessage(trimToLength(message, MAX_MESSAGE_LENGTH));
		entity.setOccurredAt(Instant.now());
		operationalEventRepository.save(entity);
		operationalEventRepository.deleteByOccurredAtBefore(entity.getOccurredAt().minus(RETENTION));
	}

	private static MonitorDtos.OperationalEventSummary toSummary(OperationalEventEntity entity) {
		return new MonitorDtos.OperationalEventSummary(
				entity.getId(),
				entity.getLevel(),
				entity.getCategory(),
				entity.getEventType(),
				entity.getSourceId(),
				entity.getCorrelationId(),
				entity.getProviderType(),
				entity.getProviderKey(),
				entity.getErrorCode(),
				entity.getMessage(),
				entity.getOccurredAt());
	}

	private static String providerJobMessage(ProviderJobEntity job, String errorCode) {
		String provider = blankToNull(job.getProviderKey());
		String target = provider == null ? "Provider" : provider;
		if (errorCode == null) {
			return target + " の " + job.getJobType() + " ジョブが " + job.getStatus() + " になりました。";
		}
		ProviderErrorCode normalized = ProviderErrorCode.normalize(errorCode);
		String reason = switch (normalized) {
			case PROVIDER_TIMEOUT -> "応答待ちでタイムアウトしました。実生成用 timeout とモデルのコールドスタート時間を確認してください。";
			case PROVIDER_UNREACHABLE -> "接続できませんでした。接続先URLとネットワークを確認してください。";
			case PROVIDER_BAD_RESPONSE -> "期待した形式の応答を返しませんでした。adapter、モデル、構造化出力を確認してください。";
			case PROVIDER_RESOURCE_EXHAUSTED -> "処理資源が不足しています。Provider のキュー、メモリ、同時実行数を確認してください。";
			case PROVIDER_AUTH_FAILED -> "認証に失敗しました。秘密値の参照先と権限を確認してください。";
			case PROVIDER_REJECTED -> "要求が拒否されました。モデル名、入力制約、Provider 設定を確認してください。";
			case PROVIDER_INTERRUPTED -> "処理が中断されました。再起動やジョブ回復履歴を確認してください。";
			case VOICE_REF_NOT_FOUND -> "音声参照が見つかりません。voice profile を確認してください。";
			case VOICE_CONSENT_REQUIRED -> "音声利用の同意条件を満たしていません。";
		};
		return target + " の " + job.getJobType() + " ジョブが失敗しました。" + reason;
	}

	private static String normalizeToken(String value, String fallback) {
		String normalized = blankToNull(value);
		return normalized == null ? fallback : normalized.toUpperCase(Locale.ROOT);
	}

	private static String trimToLength(String value, int maxLength) {
		String normalized = blankToNull(value);
		return normalized == null ? null : normalized.substring(0, Math.min(normalized.length(), maxLength));
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
