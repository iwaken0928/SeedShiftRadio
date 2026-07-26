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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.seedshiftradio.letter.LetterChangedEvent;
import com.seedshiftradio.radio.RadioEventRecord;

@Service
public class StreamEventService {

	private static final int MAX_HISTORY = 100;

	private final AtomicLong sequence = new AtomicLong();
	private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
	private final List<RadioEventRecord> history = new ArrayList<>();

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
		RadioEventRecord event = new RadioEventRecord(Long.toString(sequence.incrementAndGet()), eventName, Instant.now(), payload);
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
		publish("letter.updated", event.summary());
	}

	public List<RadioEventRecord> replayAfter(String lastEventId) {
		if (lastEventId == null || lastEventId.isBlank()) {
			return List.of();
		}
		long lastSeen;
		try {
			lastSeen = Long.parseLong(lastEventId);
		} catch (NumberFormatException exception) {
			return List.of();
		}
		synchronized (history) {
			return history.stream()
					.filter(event -> Long.parseLong(event.id()) > lastSeen)
					.toList();
		}
	}

	@Scheduled(fixedDelayString = "${seedshift.radio.stream.heartbeat-delay:15s}")
	public void sendHeartbeat() {
		emitters.forEach((emitterId, emitter) -> {
			try {
				emitter.send(SseEmitter.event().comment("keepalive"));
			} catch (IOException | IllegalStateException exception) {
				emitter.completeWithError(exception);
				emitters.remove(emitterId, emitter);
			}
		});
	}

	public String latestEventId() {
		long latest = sequence.get();
		return latest == 0 ? null : Long.toString(latest);
	}

	public List<RadioEventRecord> recentEvents(int limit) {
		if (limit <= 0) {
			return List.of();
		}
		synchronized (history) {
			int start = Math.max(0, history.size() - limit);
			ArrayList<RadioEventRecord> recent = new ArrayList<>(history.subList(start, history.size()));
			java.util.Collections.reverse(recent);
			return List.copyOf(recent);
		}
	}

	private void sendReplay(SseEmitter emitter, String lastEventId) {
		for (RadioEventRecord event : replayAfter(lastEventId)) {
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

	private void send(SseEmitter emitter, RadioEventRecord event) throws IOException {
		emitter.send(SseEmitter.event()
				.id(event.id())
				.name(event.eventType())
				.data(event.payload()));
	}
}
