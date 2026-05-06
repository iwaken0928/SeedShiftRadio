package com.seedshiftradio.settings;

public interface MusicGenerationProvider {

	MusicGenWorkerGateway.SubmittedMusicJob submit(
			MusicGenWorkerGateway.ResolvedMusicProvider provider,
			MusicGenerationRequest request);

	MusicGenWorkerGateway.MusicJobStatus poll(
			MusicGenWorkerGateway.ResolvedMusicProvider provider,
			String providerTaskId);

	MusicGenWorkerGateway.ModelCatalog listModels(MusicGenWorkerGateway.ResolvedMusicProvider provider);

	MusicGenWorkerGateway.RuntimeStats stats(MusicGenWorkerGateway.ResolvedMusicProvider provider);
}
