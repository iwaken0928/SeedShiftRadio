package com.seedshiftradio.radio;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class ClientCapabilitiesService {

	private final ClientCapabilitiesRepository clientCapabilitiesRepository;

	public ClientCapabilitiesService(ClientCapabilitiesRepository clientCapabilitiesRepository) {
		this.clientCapabilitiesRepository = clientCapabilitiesRepository;
	}

	public ClientCapabilitiesResponse register(ClientCapabilitiesRequest request, String correlationId) {
		Instant acceptedAt = Instant.now();
		ClientCapabilitiesEntity entity = clientCapabilitiesRepository.findById(request.clientId())
				.orElseGet(ClientCapabilitiesEntity::new);
		entity.setClientId(request.clientId());
		entity.setClientType(request.clientType());
		entity.setSupportsClientSideTts(request.supportsClientSideTts());
		entity.setSupportedVoiceEngines(request.supportedVoiceEngines() == null ? List.of() : List.copyOf(request.supportedVoiceEngines()));
		entity.setPreferredPlaybackMode(request.preferredPlaybackMode());
		entity.setLocalVoiceProfiles(toStoredProfiles(request.localVoiceProfiles()));
		entity.setAcceptedAt(acceptedAt);
		ClientCapabilitiesEntity saved = clientCapabilitiesRepository.save(entity);
		ClientCapabilitiesRecord record = toRecord(saved);
		return new ClientCapabilitiesResponse(
				record.clientId(),
				record.clientType(),
				record.supportsClientSideTts(),
				record.supportedVoiceEngines(),
				record.preferredPlaybackMode(),
				record.localVoiceProfiles(),
				record.acceptedAt(),
				correlationId);
	}

	public ClientCapabilitiesRecord latest(String clientId) {
		return clientCapabilitiesRepository.findById(clientId)
				.map(this::toRecord)
				.orElse(null);
	}

	public List<ClientCapabilitiesRecord> snapshot() {
		return clientCapabilitiesRepository.findAll().stream()
				.map(this::toRecord)
				.toList();
	}

	private ClientCapabilitiesRecord toRecord(ClientCapabilitiesEntity entity) {
		return new ClientCapabilitiesRecord(
				entity.getClientId(),
				entity.getClientType(),
				entity.isSupportsClientSideTts(),
				entity.getSupportedVoiceEngines() == null ? List.of() : List.copyOf(entity.getSupportedVoiceEngines()),
				entity.getPreferredPlaybackMode(),
				(entity.getLocalVoiceProfiles() == null ? List.<ClientCapabilitiesEntity.StoredLocalVoiceProfile>of() : entity.getLocalVoiceProfiles()).stream()
						.map(profile -> new ClientCapabilitiesRequest.LocalVoiceProfile(profile.getEngine(), profile.getProfileKey()))
						.toList(),
				entity.getAcceptedAt());
	}

	private List<ClientCapabilitiesEntity.StoredLocalVoiceProfile> toStoredProfiles(List<ClientCapabilitiesRequest.LocalVoiceProfile> localVoiceProfiles) {
		if (localVoiceProfiles == null || localVoiceProfiles.isEmpty()) {
			return List.of();
		}
		return localVoiceProfiles.stream()
				.map(profile -> new ClientCapabilitiesEntity.StoredLocalVoiceProfile(profile.engine(), profile.profileKey()))
				.toList();
	}
}
