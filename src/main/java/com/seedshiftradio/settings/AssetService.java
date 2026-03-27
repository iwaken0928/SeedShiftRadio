package com.seedshiftradio.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.seedshiftradio.common.api.ApiException;

@Service
public class AssetService {

	private final RadioSettingsStore settingsStore;

	public AssetService(RadioSettingsStore settingsStore) {
		this.settingsStore = settingsStore;
	}

	public byte[] loadAudio(String assetId, Supplier<byte[]> placeholderSupplier) {
		SettingsDocument settings = settingsStore.load();
		Path dataRoot = Path.of(settings.paths().dataRoot()).toAbsolutePath().normalize();
		Path audioRoot = dataRoot.resolve("assets").resolve("audio").normalize();
		Path assetPath = audioRoot.resolve(assetId + ".wav").normalize();
		if (!assetPath.startsWith(audioRoot)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "assetId が不正です。", Map.of("assetId", assetId));
		}
		if (Files.exists(assetPath)) {
			try {
				return Files.readAllBytes(assetPath);
			} catch (IOException exception) {
				throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "音声 asset の読み込みに失敗しました。", Map.of("assetPath", assetPath.toString()));
			}
		}
		if (Boolean.TRUE.equals(settings.features().allowPlaceholderAudio())) {
			return placeholderSupplier.get();
		}
		throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "指定された audio asset が見つかりません。", Map.of("assetId", assetId));
	}
}
