package com.seedshiftradio.stream;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.seedshiftradio.letter.LetterChangedEvent;

@Service
public class StreamEventService {

	private static final int MAX_HISTORY = 100;

	private final AtomicLong sequence = new AtomicLong();
	private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
	private final List<EventEnvelope> history = new ArrayList<>();

	public SseEmitter subscribe(String lastEventId) {
		SseEmitter emitter = new SseEmitter(0L);
		String emitterId = UUID.randomUUID().toString();
		emitters.put(emitterId, emitter);
		emitter.onCompletion(() -> emitters.remove(emitterId));
		emitter.onTimeout(() -> emitters.remove(emitterId));
		emitter.onError(exception -> emitters.remove(emitterId));

		sendReplay(emitter, lastEventId);
		sendConnected(emitter);
		return emitter;
	}

	public void publish(String eventName, Object payload) {
		EventEnvelope event = new EventEnvelope(Long.toString(sequence.incrementAndGet()), eventName, payload, Instant.now());
		synchronized (history) {
			history.add(event);
			while (history.size() > MAX_HISTORY) {
				history.remove(0);
			}
		}
		Iterator<Map.Entry<String, SseEmitter>> iterator = emitters.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<String, SseEmitter> entry = iterator.next();
			try {
				send(entry.getValue(), event);
			} catch (IOException exception) {
				entry.getValue().completeWithError(exception);
				iterator.remove();
			}
		}
	}

	@EventListener
	public void onLetterChanged(LetterChangedEvent event) {
		publish("letter.updated", Map.of("letterId", event.letterId()));
	}

	private void sendReplay(SseEmitter emitter, String lastEventId) {
		if (lastEventId == null || lastEventId.isBlank()) {
			return;
		}
		long lastSeen;
		try {
			lastSeen = Long.parseLong(lastEventId);
		} catch (NumberFormatException exception) {
			return;
		}
		List<EventEnvelope> replay;
		synchronized (history) {
			replay = history.stream()
					.filter(event -> Long.parseLong(event.id()) > lastSeen)
					.toList();
		}
		for (EventEnvelope event : replay) {
			try {
				send(emitter, event);
			} catch (IOException exception) {
				emitter.completeWithError(exception);
				return;
			}
		}
	}

	private void sendConnected(SseEmitter emitter) {
		try {
			emitter.send(SseEmitter.event()
					.id(Long.toString(sequence.incrementAndGet()))
					.name("connected")
					.data(Map.of("connectedAt", Instant.now().toString())));
		} catch (IOException exception) {
			emitter.completeWithError(exception);
		}
	}

	private void send(SseEmitter emitter, EventEnvelope event) throws IOException {
		emitter.send(SseEmitter.event()
				.id(event.id())
				.name(event.eventName())
				.data(event.payload()));
	}

	private record EventEnvelope(String id, String eventName, Object payload, Instant createdAt) {
	}
}
