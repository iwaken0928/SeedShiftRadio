package com.seedshiftradio.radio;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seedshiftradio.common.correlation.CorrelationIdFilter;
import com.seedshiftradio.settings.AssetService;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;

@Validated
@RestController
@RequestMapping("/api")
public class RadioController {

	private final RadioService radioService;
	private final AssetService assetService;

	public RadioController(RadioService radioService, AssetService assetService) {
		this.radioService = radioService;
		this.assetService = assetService;
	}

	@PostMapping("/clients/capabilities")
	public ClientCapabilitiesResponse registerCapabilities(
			@Valid @RequestBody ClientCapabilitiesRequest request,
			HttpServletRequest httpServletRequest) {
		return radioService.registerCapabilities(request, CorrelationIdFilter.getCorrelationId(httpServletRequest));
	}

	@PostMapping("/radio/tune")
	public TuneResponse tune(
			@Valid @RequestBody TuneRequest request,
			HttpServletRequest httpServletRequest) {
		return radioService.tune(request, CorrelationIdFilter.getCorrelationId(httpServletRequest));
	}

	@PostMapping("/radio/play")
	public RadioStatusResponse play() {
		return radioService.play();
	}

	@PostMapping("/radio/stop")
	public RadioStatusResponse stop() {
		return radioService.stop();
	}

	@GetMapping("/radio/status")
	public RadioStatusResponse status() {
		return radioService.getStatus();
	}

	@GetMapping("/radio/program")
	public ProgramBlockResponse program() {
		return radioService.getProgram();
	}

	@GetMapping("/radio/queue")
	public QueueSnapshotResponse queue() {
		return radioService.getQueue();
	}

	@GetMapping("/radio/next-segment")
	public QueueItemResponse nextSegment() {
		return radioService.getNextSegment();
	}

	@GetMapping("/radio/next-speech-directive")
	public SpeechDirectiveResponse nextSpeechDirective(@RequestParam(value = "clientId", required = false) String clientId) {
		return radioService.getNextSpeechDirective(clientId);
	}

	@PostMapping("/radio/playback-events")
	public ResponseEntity<Void> playbackEvents(@Valid @RequestBody PlaybackEventRequest request) {
		radioService.recordPlaybackEvent(request);
		return ResponseEntity.accepted().build();
	}

	@GetMapping(value = "/assets/audio/{assetId}.wav", produces = "audio/wav")
	public ResponseEntity<byte[]> placeholderAudio(@PathVariable("assetId") String assetId) {
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("audio/wav"))
				.body(assetService.loadAudio(assetId, radioService::placeholderWav));
	}
}
