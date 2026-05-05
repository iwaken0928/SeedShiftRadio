package com.seedshiftradio.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seedshiftradio.common.api.ApiException;

@Service
public class RadioSettingsStore {

	private final ObjectMapper objectMapper;
	private final Path configPath;

	public RadioSettingsStore(ObjectMapper objectMapper, RadioConfigProperties properties) {
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
		this.configPath = Path.of(properties.resolvedPath()).normalize();
	}

	public synchronized SettingsDocument load() {
		if (!Files.exists(configPath)) {
			SettingsDocument defaults = SettingsDocument.defaults().normalize();
			save(defaults);
			return defaults;
		}
		try (Reader reader = Files.newBufferedReader(configPath)) {
			SettingsDocument document = objectMapper.readValue(reader, SettingsDocument.class);
			return document == null ? SettingsDocument.defaults().normalize() : document.normalize();
		} catch (IOException exception) {
			throw new ApiException(
					HttpStatus.INTERNAL_SERVER_ERROR,
					"INTERNAL_ERROR",
					"config.json の読み込みに失敗しました。",
					Map.of("configPath", configPath.toString()));
		}
	}

	public synchronized SettingsDocument save(SettingsDocument document) {
		try {
			Path parent = configPath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (Writer writer = Files.newBufferedWriter(configPath)) {
				objectMapper.writerWithDefaultPrettyPrinter().writeValue(writer, document.normalize());
			}
			return document.normalize();
		} catch (IOException exception) {
			throw new ApiException(
					HttpStatus.INTERNAL_SERVER_ERROR,
					"INTERNAL_ERROR",
					"config.json の保存に失敗しました。",
					Map.of("configPath", configPath.toString()));
		}
	}

	public Path configPath() {
		return configPath;
	}
}
