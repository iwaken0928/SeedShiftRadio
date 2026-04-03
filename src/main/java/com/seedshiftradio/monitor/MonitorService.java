package com.seedshiftradio.monitor;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

import com.seedshiftradio.domain.ProviderJobStatus;
import com.seedshiftradio.letter.LetterService;
import com.seedshiftradio.monitor.MonitorDtos.AuditEventSummary;
import com.seedshiftradio.monitor.MonitorDtos.MonitorSummaryResponse;
import com.seedshiftradio.monitor.MonitorDtos.ProviderJobSummary;
import com.seedshiftradio.radio.ProgramBlockResponse;
import com.seedshiftradio.radio.QueueSnapshotResponse;
import com.seedshiftradio.radio.RadioEventRecord;
import com.seedshiftradio.radio.RadioService;
import com.seedshiftradio.radio.RadioStatusResponse;
import com.seedshiftradio.radio.SubtitlePayload;
import com.seedshiftradio.settings.ProviderJobEntity;
import com.seedshiftradio.settings.ProviderJobRepository;
import com.seedshiftradio.settings.ProviderHealthService;
import com.seedshiftradio.stream.StreamEventService;

@Service
public class MonitorService {

	private static final int JOB_LIMIT = 10;
	private static final int AUDIT_FETCH_LIMIT = 20;
	private static final int AUDIT_RESULT_LIMIT = 10;

	private final RadioService radioService;
	private final LetterService letterService;
	private final ProviderHealthService providerHealthService;
	private final ProviderJobRepository providerJobRepository;
	private final StreamEventService streamEventService;

	public MonitorService(
			RadioService radioService,
			LetterService letterService,
			ProviderHealthService providerHealthService,
			ProviderJobRepository providerJobRepository,
			StreamEventService streamEventService) {
		this.radioService = radioService;
		this.letterService = letterService;
		this.providerHealthService = providerHealthService;
		this.providerJobRepository = providerJobRepository;
		this.streamEventService = streamEventService;
	}

	public MonitorSummaryResponse summary() {
		RadioStatusResponse status = radioService.getStatus();
		long pendingLetters = status.stationId() == null ? 0 : letterService.countPendingLetters(status.stationId());
		List<ProviderJobSummary> runningJobs = providerJobRepository.findTop10ByStatusOrderByUpdatedAtDesc(ProviderJobStatus.RUNNING).stream()
				.map(MonitorService::toJobSummary)
				.toList();
		List<ProviderJobSummary> recentErrors = providerJobRepository.findTop10ByStatusOrderByUpdatedAtDesc(ProviderJobStatus.FAILED).stream()
				.map(MonitorService::toJobSummary)
				.toList();
		List<AuditEventSummary> auditEvents = streamEventService.recentEvents(AUDIT_FETCH_LIMIT).stream()
				.filter(MonitorService::isAuditEvent)
				.map(MonitorService::toAuditEventSummary)
				.limit(AUDIT_RESULT_LIMIT)
				.toList();
		return new MonitorSummaryResponse(
				status.sessionId(),
				status.stationId(),
				status.state(),
				status.bufferReadyCount(),
				pendingLetters,
				status.degraded(),
				providerHealthService.getLatestOrProbe(),
				runningJobs,
				recentErrors,
				auditEvents,
				status.updatedAt());
	}

	private static ProviderJobSummary toJobSummary(ProviderJobEntity entity) {
		return new ProviderJobSummary(
				entity.getId(),
				entity.getJobType(),
				entity.getProviderType(),
				entity.getProviderKey(),
				entity.getQueueItemId(),
				entity.getStatus(),
				entity.getExternalRef(),
				entity.getErrorCode(),
				entity.getStartedAt(),
				entity.getEndedAt(),
				entity.getCreatedAt(),
				entity.getUpdatedAt());
	}

	private static boolean isAuditEvent(RadioEventRecord event) {
		return switch (event.eventType()) {
			case "radio.status.changed", "queue.updated", "program.changed", "subtitle.updated",
					"provider.health.changed", "buffer.warning", "letter.updated",
					"provider.job.queued", "provider.job.running", "provider.job.succeeded", "provider.job.failed" -> true;
			default -> false;
		};
	}

	private static AuditEventSummary toAuditEventSummary(RadioEventRecord event) {
		return new AuditEventSummary(
				event.id(),
				event.eventType(),
				event.occurredAt(),
				summarizeEvent(event));
	}

	private static String summarizeEvent(RadioEventRecord event) {
		Object payload = event.payload();
		return switch (event.eventType()) {
			case "radio.status.changed" -> summarizeStatus(payload);
			case "queue.updated" -> summarizeQueue(payload);
			case "program.changed" -> summarizeProgram(payload);
			case "subtitle.updated" -> summarizeSubtitle(payload);
			case "buffer.warning" -> summarizeBuffer(payload);
			case "letter.updated" -> summarizeLetter(payload);
			case "provider.health.changed" -> "provider health changed";
			case "provider.job.queued", "provider.job.running", "provider.job.succeeded", "provider.job.failed" -> summarizeJob(payload);
			default -> payload == null ? event.eventType() : payload.toString();
		};
	}

	private static String summarizeStatus(Object payload) {
		if (payload instanceof RadioStatusResponse status) {
			return "station=" + valueOrNone(status.stationId()) + ", state=" + status.state() + ", currentItem=" + valueOrNone(status.currentItemId());
		}
		return "radio status changed";
	}

	private static String summarizeQueue(Object payload) {
		if (payload instanceof QueueSnapshotResponse queue) {
			return "queue items=" + queue.items().size() + ", station=" + valueOrNone(queue.stationId());
		}
		return "queue updated";
	}

	private static String summarizeProgram(Object payload) {
		if (payload instanceof ProgramBlockResponse program) {
			return "program=" + program.title() + ", status=" + program.status();
		}
		return "program changed";
	}

	private static String summarizeSubtitle(Object payload) {
		if (payload instanceof SubtitlePayload subtitle) {
			String text = subtitle.text() == null ? "" : subtitle.text().replaceAll("\\s+", " ").trim();
			if (text.length() > 80) {
				text = text.substring(0, 77) + "...";
			}
			return valueOrNone(subtitle.itemId()) + " / " + text;
		}
		return "subtitle updated";
	}

	private static String summarizeBuffer(Object payload) {
		if (payload instanceof Map<?, ?> map) {
			return "session=" + valueOrNone(stringValue(map.get("sessionId"))) + ", ready=" + valueOrNone(stringValue(map.get("readyCount")));
		}
		return "buffer warning";
	}

	private static String summarizeLetter(Object payload) {
		if (payload instanceof com.seedshiftradio.letter.LetterDtos.LetterSummaryResponse letter) {
			return "letter=" + letter.id() + ", status=" + letter.status();
		}
		return "letter updated";
	}

	private static String summarizeJob(Object payload) {
		if (payload instanceof Map<?, ?> map) {
			return "job=" + valueOrNone(stringValue(map.get("providerJobId")))
					+ ", type=" + valueOrNone(stringValue(map.get("jobType")))
					+ ", status=" + valueOrNone(stringValue(map.get("status")))
					+ ", error=" + valueOrNone(stringValue(map.get("errorCode")));
		}
		return "provider job changed";
	}

	private static String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static String valueOrNone(String value) {
		if (value == null || value.isBlank() || "null".equals(value)) {
			return "none";
		}
		return value;
	}
}
