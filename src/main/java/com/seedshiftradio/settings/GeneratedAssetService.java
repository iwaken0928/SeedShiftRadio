package com.seedshiftradio.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.GeneratedAssetType;

@Service
public class GeneratedAssetService {

	private final GeneratedAssetRepository generatedAssetRepository;
	private final RadioSettingsStore settingsStore;

	public GeneratedAssetService(GeneratedAssetRepository generatedAssetRepository, RadioSettingsStore settingsStore) {
		this.generatedAssetRepository = generatedAssetRepository;
		this.settingsStore = settingsStore;
	}

	@Transactional
	public GeneratedAssetEntity createAudioAsset(byte[] bytes, String providerFingerprint, Map<String, Object> metadata) {
		return createAudioAsset(bytes, providerFingerprint, null, null, metadata);
	}

	@Transactional
	public GeneratedAssetEntity createAudioAsset(
			byte[] bytes,
			String providerFingerprint,
			String queueItemId,
			String providerJobId,
			Map<String, Object> metadata) {
		String assetId = nextId();
		Path assetPath = resolveAudioPath(assetId);
		write(assetPath, bytes);
		return persistAsset(
				assetId,
				GeneratedAssetType.AUDIO,
				assetPath,
				bytes,
				providerFingerprint,
				queueItemId,
				providerJobId,
				metadata);
	}

	@Transactional
	public GeneratedAssetEntity registerExistingAsset(
			GeneratedAssetType assetType,
			Path assetPath,
			String providerFingerprint,
			String queueItemId,
			String providerJobId,
			Map<String, Object> metadata) {
		Path normalizedPath = assetPath.toAbsolutePath().normalize();
		byte[] bytes = read(normalizedPath);
		return persistAsset(
				nextId(),
				assetType,
				normalizedPath,
				bytes,
				providerFingerprint,
				queueItemId,
				providerJobId,
				metadata);
	}

	public Optional<Path> resolveAudioAssetPath(String assetId) {
		return generatedAssetRepository.findById(assetId)
				.map(entity -> Path.of(entity.getStoragePath()).toAbsolutePath().normalize());
	}

	public Path resolveLegacyAudioPath(String assetId) {
		return resolveAudioPath(assetId);
	}

	private Path resolveAudioPath(String assetId) {
		Path dataRoot = Path.of(settingsStore.load().paths().dataRoot()).toAbsolutePath().normalize();
		Path audioRoot = dataRoot.resolve("assets").resolve("audio").normalize();
		Path assetPath = audioRoot.resolve(assetId + ".wav").normalize();
		if (!assetPath.startsWith(audioRoot)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "assetId が不正です。", Map.of("assetId", assetId));
		}
		return assetPath;
	}

	private void write(Path assetPath, byte[] bytes) {
		try {
			Files.createDirectories(assetPath.getParent());
			Files.write(assetPath, bytes);
		} catch (IOException exception) {
			throw new ApiException(
					HttpStatus.INTERNAL_SERVER_ERROR,
					"INTERNAL_ERROR",
					"generated asset の保存に失敗しました。",
					Map.of("assetPath", assetPath.toString()));
		}
	}

	private byte[] read(Path assetPath) {
		try {
			return Files.readAllBytes(assetPath);
		} catch (IOException exception) {
			throw new ApiException(
					HttpStatus.INTERNAL_SERVER_ERROR,
					"INTERNAL_ERROR",
					"generated asset の読み込みに失敗しました。",
					Map.of("assetPath", assetPath.toString()));
		}
	}

	private GeneratedAssetEntity persistAsset(
			String assetId,
			GeneratedAssetType assetType,
			Path assetPath,
			byte[] bytes,
			String providerFingerprint,
			String queueItemId,
			String providerJobId,
			Map<String, Object> metadata) {
		GeneratedAssetEntity entity = new GeneratedAssetEntity();
		entity.setId(assetId);
		entity.setAssetType(assetType);
		entity.setStoragePath(assetPath.toString());
		entity.setContentHash(sha256(bytes));
		entity.setProviderFingerprint(providerFingerprint == null || providerFingerprint.isBlank() ? "server:placeholder" : providerFingerprint);
		entity.setQueueItemId(queueItemId);
		entity.setProviderJobId(providerJobId);
		entity.setMetadata(metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata));
		entity.setUpdatedAt(Instant.now());
		return generatedAssetRepository.save(entity);
	}

	private String sha256(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(bytes);
			StringBuilder builder = new StringBuilder(hash.length * 2);
			for (byte value : hash) {
				builder.append(String.format("%02x", value));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 が利用できません。", exception);
		}
	}

	private String nextId() {
		return "asset-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
