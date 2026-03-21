package com.seedshiftradio.stream;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.seedshiftradio.radio.RadioEventRecord;

@Component
public class RadioEventHub {

	private final AtomicLong sequence = new AtomicLong(0L);
	private final List<RadioEventRecord> events = new ArrayList<>();
	private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

	public RadioEventRecord publish(String eventType, Object payload) {
		RadioEventRecord event = new RadioEventRecord(nextId(), eventType, Instant.now(), payload);
		synchronized (events) {
			events.add(event);
		}
		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(SseEmitter.event().id(event.id()).name(event.eventType()).data(payload));
			} catch (Exception exception) {
				emitters.remove(emitter);
			}
		}
		return event;
	}

	public void register(SseEmitter emitter) {
		emitters.add(emitter);
		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError(exception -> emitters.remove(emitter));
	}

	public List<RadioEventRecord> replayAfter(String lastEventId) {
		synchronized (events) {
			if (lastEventId == null || lastEventId.isBlank()) {
				return List.copyOf(events);
			}
			int index = -1;
			for (int i = 0; i < events.size(); i++) {
				if (events.get(i).id().equals(lastEventId)) {
					index = i;
				}
			}
			if (index < 0 || index + 1 >= events.size()) {
				return Collections.emptyList();
			}
			return List.copyOf(events.subList(index + 1, events.size()));
		}
	}

	public String latestEventId() {
		synchronized (events) {
			return events.isEmpty() ? null : events.get(events.size() - 1).id();
		}
	}

	private String nextId() {
		return String.format("evt-%06d", sequence.incrementAndGet());
	}
}
