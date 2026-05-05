package com.seedshiftradio.stream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/stream/events")
public class StreamController {

	private final StreamEventService streamEventService;

	public StreamController(StreamEventService streamEventService) {
		this.streamEventService = streamEventService;
	}

	@GetMapping
	public SseEmitter subscribe(@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
		return streamEventService.subscribe(lastEventId);
	}
}
