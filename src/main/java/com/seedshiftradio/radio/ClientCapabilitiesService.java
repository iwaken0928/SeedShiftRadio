package com.seedshiftradio.radio;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class ClientCapabilitiesService {

	private final Map<String, ClientCapabilitiesRecord> capabilitiesByClientId = new ConcurrentHashMap<>();

	public ClientCapabilitiesResponse register(ClientCapabilitiesRequest request, String correlationId) {
		ClientCapabilitiesRecord record = new ClientCapabilitiesRecord(
				request.clientId(),
				request.clientType(),
				request.supportsClientSideTts(),
				request.supportedVoiceEngines(),
				request.preferredPlaybackMode(),
				request.localVoiceProfiles(),
				Instant.now());
		capabilitiesByClientId.put(request.clientId(), record);
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
		return capabilitiesByClientId.get(clientId);
	}

	public List<ClientCapabilitiesRecord> snapshot() {
		return List.copyOf(capabilitiesByClientId.values());
	}
}
