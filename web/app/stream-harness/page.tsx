"use client";

import { useEffect, useState } from "react";
import { Button, Card, SectionHeader } from "@/components/ui";
import { openSeedShiftStream, type StreamStatus } from "@/lib/sse";
import type { EventMessage } from "@/lib/types";

export default function StreamHarnessPage() {
  const [status, setStatus] = useState<StreamStatus>("idle");
  const [lastEventId, setLastEventId] = useState("");
  const [subtitle, setSubtitle] = useState("");
  const [streamVersion, setStreamVersion] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    const stop = openSeedShiftStream({
      signal: controller.signal,
      initialLastEventId: lastEventId || null,
      onStatus: setStatus,
      onEvent: (event: EventMessage) => {
        if (event.id) {
          setLastEventId(event.id);
        }
        if (event.event === "subtitle.updated") {
          setSubtitle(extractSubtitle(event.data));
        }
      },
    });

    return () => {
      stop();
      controller.abort();
    };
  }, [streamVersion]);

  return (
    <Card>
      <SectionHeader
        eyebrow="E2E"
        title="Stream Harness"
        description="Playwright から SSE reconnect と Last-Event-ID を検証するための内部ページです。"
        action={
          <Button type="button" tone="secondary" data-testid="stream-harness-reconnect" onClick={() => setStreamVersion((value) => value + 1)}>
            Reconnect
          </Button>
        }
      />
      <dl className="grid gap-3 md:grid-cols-3">
        <div>
          <dt className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Status</dt>
          <dd className="mt-2 text-sm font-semibold text-slate-950" data-testid="stream-harness-status">
            {status}
          </dd>
        </div>
        <div>
          <dt className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Last Event ID</dt>
          <dd className="mt-2 text-sm font-semibold text-slate-950" data-testid="stream-harness-last-event-id">
            {lastEventId}
          </dd>
        </div>
        <div>
          <dt className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Subtitle</dt>
          <dd className="mt-2 text-sm font-semibold text-slate-950" data-testid="stream-harness-subtitle">
            {subtitle}
          </dd>
        </div>
      </dl>
    </Card>
  );
}

function extractSubtitle(data: unknown) {
  if (typeof data === "string") {
    return data;
  }
  if (data && typeof data === "object" && "text" in data && typeof (data as { text?: unknown }).text === "string") {
    return (data as { text: string }).text;
  }
  return "";
}
