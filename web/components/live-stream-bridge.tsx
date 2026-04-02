"use client";

import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { openSeedShiftStream, type StreamStatus } from "@/lib/sse";
import { useUiStore } from "@/stores/ui-store";
import type { EventMessage, HealthResponse, MonitorSummary, ProgramBlockSummary, QueueSnapshot, RadioStatus, ProviderHealthPayload } from "@/lib/types";

export function LiveStreamBridge() {
  const queryClient = useQueryClient();
  const pushEvent = useUiStore((state) => state.pushEvent);
  const setConnectionStatus = useUiStore((state) => state.setConnectionStatus);
  const setLastEventId = useUiStore((state) => state.setLastEventId);
  const lastEventId = useUiStore((state) => state.lastEventId);

  useEffect(() => {
    const controller = new AbortController();
    const stop = openSeedShiftStream({
      signal: controller.signal,
      initialLastEventId: lastEventId,
      onStatus: (status: StreamStatus) => setConnectionStatus(status),
      onEvent: (event: EventMessage) => {
        if (event.id) {
          setLastEventId(event.id);
        }
        pushEvent({
          id: event.id,
          event: event.event,
          at: new Date().toISOString(),
          summary: summarizeEvent(event),
        });
        syncCache(queryClient, event);
      },
    });

    return () => {
      stop();
      controller.abort();
    };
  }, [lastEventId, pushEvent, queryClient, setConnectionStatus, setLastEventId]);

  return null;
}

function syncCache(queryClient: ReturnType<typeof useQueryClient>, event: EventMessage) {
  switch (event.event) {
    case "radio.status.changed":
      queryClient.setQueryData(["radio", "status"], event.data as RadioStatus);
      break;
    case "queue.updated":
      queryClient.setQueryData(["radio", "queue"], event.data as QueueSnapshot);
      break;
    case "program.changed":
      queryClient.setQueryData(["radio", "program"], event.data as ProgramBlockSummary);
      break;
    case "provider.health.changed":
      queryClient.setQueryData(["health"], (current: HealthResponse | undefined) =>
        current
          ? {
              ...current,
              providerHealth: event.data as Record<string, ProviderHealthPayload>,
            }
          : current,
      );
      queryClient.setQueryData(["monitor", "summary"], (current: MonitorSummary | undefined) =>
        current
          ? {
              ...current,
              providerHealth: event.data as Record<string, ProviderHealthPayload>,
            }
          : current,
      );
      break;
    case "letter.updated":
      queryClient.invalidateQueries({ queryKey: ["letters"] });
      break;
    case "subtitle.updated":
      useUiStore.getState().setLiveSubtitle(extractSubtitle(event.data));
      break;
    default:
      break;
  }
}

function summarizeEvent(event: EventMessage) {
  if (event.event === "subtitle.updated") {
    return extractSubtitle(event.data);
  }
  if (event.event === "provider.health.changed") {
    return "Provider health updated";
  }
  if (event.event === "radio.status.changed") {
    const status = event.data as RadioStatus;
    return `Radio ${status.state}`;
  }
  if (event.event === "queue.updated") {
    const queue = event.data as QueueSnapshot;
    return `${queue.items.length} queue items`;
  }
  if (event.event === "program.changed") {
    const program = event.data as ProgramBlockSummary;
    return program.title;
  }
  if (event.event === "letter.updated") {
    return "Letter updated";
  }
  return typeof event.data === "string" ? event.data : event.event;
}

function extractSubtitle(data: unknown) {
  if (typeof data === "string") {
    return data;
  }
  if (data && typeof data === "object" && "text" in data && typeof (data as { text?: unknown }).text === "string") {
    return (data as { text: string }).text;
  }
  return "Subtitle updated";
}
