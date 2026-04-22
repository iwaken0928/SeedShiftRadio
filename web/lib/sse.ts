import { getApiBaseUrl } from "@/lib/env";
import type { EventMessage } from "@/lib/types";

type StreamHandlers = {
  onEvent: (event: EventMessage) => void;
  onStatus?: (status: StreamStatus) => void;
  signal: AbortSignal;
  initialLastEventId?: string | null;
};

export type StreamStatus = "idle" | "connecting" | "connected" | "reconnecting" | "closed" | "error";

export function openSeedShiftStream({ onEvent, onStatus, signal, initialLastEventId }: StreamHandlers) {
  let lastEventId = initialLastEventId ?? null;
  let retryDelay = 1000;
  let active = true;

  const run = async () => {
    while (active && !signal.aborted) {
      try {
        onStatus?.(retryDelay === 1000 ? "connecting" : "reconnecting");
        const response = await fetch(`${getApiBaseUrl()}/api/stream/events`, {
          signal,
          cache: "no-store",
          headers: {
            Accept: "text/event-stream",
            ...(lastEventId ? { "Last-Event-ID": lastEventId } : {}),
          },
        });
        if (!response.ok || !response.body) {
          throw new Error(`SSE request failed: ${response.status}`);
        }

        onStatus?.("connected");
        retryDelay = 1000;
        lastEventId = await consumeStream(response.body, onEvent, (eventId) => {
          lastEventId = eventId;
        }, signal, lastEventId);
      } catch (error) {
        if (signal.aborted || !active) {
          break;
        }
        onStatus?.("error");
        await wait(retryDelay, signal);
        retryDelay = Math.min(Math.round(retryDelay * 1.8), 10_000);
      }
    }
    onStatus?.("closed");
  };

  void run();

  return () => {
    active = false;
  };
}

async function consumeStream(
  body: ReadableStream<Uint8Array>,
  onEvent: (event: EventMessage) => void,
  onId: (id: string) => void,
  signal: AbortSignal,
  lastKnownEventId: string | null,
) {
  const reader = body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let eventName = "message";
  let eventId = lastKnownEventId;
  let lastReceivedEventId = lastKnownEventId;
  let dataLines: string[] = [];

  const flush = () => {
    if (!dataLines.length && !eventId && eventName === "message") {
      return;
    }
    const rawData = dataLines.join("\n");
    const data = parseEventData(rawData);
    const nextEventId = eventId ?? null;
    if (nextEventId) {
      lastReceivedEventId = nextEventId;
      onId(nextEventId);
    }
    onEvent({
      id: nextEventId,
      event: eventName,
      data,
    });
    eventName = "message";
    eventId = null;
    dataLines = [];
  };

  while (true) {
    if (signal.aborted) {
      break;
    }
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    let lineBreakIndex = buffer.indexOf("\n");
    while (lineBreakIndex >= 0) {
      const rawLine = buffer.slice(0, lineBreakIndex).replace(/\r$/, "");
      buffer = buffer.slice(lineBreakIndex + 1);
      if (rawLine === "") {
        flush();
      } else if (rawLine.startsWith("event:")) {
        eventName = rawLine.slice(6).trim() || "message";
      } else if (rawLine.startsWith("id:")) {
        eventId = rawLine.slice(3).trim() || null;
      } else if (rawLine.startsWith("data:")) {
        dataLines.push(rawLine.slice(5).trimStart());
      }
      lineBreakIndex = buffer.indexOf("\n");
    }
  }

  flush();
  return lastReceivedEventId;
}

function parseEventData(rawData: string) {
  if (!rawData) {
    return null;
  }
  try {
    return JSON.parse(rawData) as unknown;
  } catch {
    return rawData;
  }
}

function wait(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const timeout = window.setTimeout(() => {
      cleanup();
      resolve();
    }, ms);

    const cleanup = () => {
      window.clearTimeout(timeout);
      signal.removeEventListener("abort", onAbort);
    };

    const onAbort = () => {
      cleanup();
      reject(new DOMException("Aborted", "AbortError"));
    };

    signal.addEventListener("abort", onAbort, { once: true });
  });
}
