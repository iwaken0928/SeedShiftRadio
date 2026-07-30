package com.seedshiftradio.settings;

import java.time.Instant;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.settings.GpuExecutionCoordinator.ExecutionOrigin;
import com.seedshiftradio.settings.GpuExecutionCoordinator.InferenceWorkload;

@Service
public class AceStepModelService {

	private final RadioSettingsStore settingsStore;
	private final MusicGenWorkerGateway musicGenWorkerGateway;
	private final GpuExecutionCoordinator gpuExecutionCoordinator;

	@Autowired
	public AceStepModelService(
			RadioSettingsStore settingsStore,
			MusicGenWorkerGateway musicGenWorkerGateway,
			GpuExecutionCoordinator gpuExecutionCoordinator) {
		this.settingsStore = settingsStore;
		this.musicGenWorkerGateway = musicGenWorkerGateway;
		this.gpuExecutionCoordinator = gpuExecutionCoordinator;
	}

	AceStepModelService(RadioSettingsStore settingsStore, MusicGenWorkerGateway musicGenWorkerGateway) {
		this(settingsStore, musicGenWorkerGateway, null);
	}

	public SettingsDtos.AceStepModelLoadResponse loadProfile(
			String providerKey,
			SettingsDtos.AceStepModelLoadRequest request) {
		SettingsDocument.ProviderGroup group = settingsStore.load().providers().musicGen();
		SettingsDocument.ProviderEndpoint endpoint = group.providers().get(providerKey);
		if (endpoint == null) {
			throw validation("providerKey", "指定した音楽生成 provider が設定に存在しません。", providerKey);
		}
		String adapter = endpoint.adapter() == null ? "" : endpoint.adapter();
		if (!"ACE_STEP".equals(adapter) && !endpoint.capabilities().contains("ACE_STEP")) {
			throw validation("providerKey", "指定した provider は ACE-Step 接続ではありません。", providerKey);
		}
		SettingsDocument.MusicGenerationModelProfile profile = endpoint.modelProfiles().get(request.profileId());
		if (profile == null) {
			throw validation("profileId", "指定した生成プロファイルが保存済み設定に存在しません。", request.profileId());
		}
		int slot = request.slot() == null ? 1 : request.slot();
		if (slot < 1 || slot > 3) {
			throw validation("slot", "slot は 1 から 3 の範囲で指定してください。", slot);
		}

		MusicGenWorkerGateway.ResolvedMusicProvider provider = musicGenWorkerGateway.resolveProvider(providerKey);
		if (gpuExecutionCoordinator != null) {
			return gpuExecutionCoordinator.execute(
					ExecutionOrigin.MANUAL,
					InferenceWorkload.MUSIC,
					providerKey,
					() -> loadValidatedProfile(providerKey, request.profileId(), slot, provider, profile));
		}
		return loadValidatedProfile(providerKey, request.profileId(), slot, provider, profile);
	}

	private SettingsDtos.AceStepModelLoadResponse loadValidatedProfile(
			String providerKey,
			String profileId,
			int slot,
			MusicGenWorkerGateway.ResolvedMusicProvider provider,
			SettingsDocument.MusicGenerationModelProfile profile) {
		MusicGenWorkerGateway.ModelCatalog catalog = musicGenWorkerGateway.listModels(provider);
		boolean modelAvailable = catalog.models().stream()
				.map(MusicGenWorkerGateway.ModelInfo::name)
				.anyMatch(model -> modelMatches(model, profile.model()));
		if (!modelAvailable) {
			throw validation("profileId", "生成プロファイルのモデルが ACE-Step のモデル一覧に存在しません。", profileId);
		}

		MusicGenWorkerGateway.ModelInitializationResult result = musicGenWorkerGateway.initializeModel(
				provider,
				profile,
				slot);
		return new SettingsDtos.AceStepModelLoadResponse(
				providerKey,
				profileId,
				result.slot() == null ? slot : result.slot(),
				result.loadedModel(),
				result.loadedLmModel(),
				result.models(),
				result.lmModels(),
				result.llmInitialized(),
				result.message(),
				Instant.now());
	}

	private ApiException validation(String field, String message, Object value) {
		return new ApiException(
				HttpStatus.BAD_REQUEST,
				"VALIDATION_ERROR",
				message,
				Map.of("field", field, "value", value));
	}

	private boolean modelMatches(String catalogModel, String selectedModel) {
		return catalogModel != null
				&& selectedModel != null
				&& (catalogModel.equals(selectedModel)
						|| catalogModel.endsWith("/" + selectedModel)
						|| catalogModel.endsWith(" " + selectedModel));
	}
}
