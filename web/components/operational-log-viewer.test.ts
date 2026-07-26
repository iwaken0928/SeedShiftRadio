import { describe, expect, it } from "vitest";
import { eventTypeLabel, filterOperationalEvents, jobStatusLabel } from "./operational-log-viewer";
import type { OperationalEvent } from "@/lib/types";

const EVENTS: OperationalEvent[] = [
  {
    id: "oplog-1",
    level: "ERROR",
    category: "PROVIDER_JOB",
    eventType: "provider.job.failed",
    sourceId: "provider-job-1",
    correlationId: "corr-1",
    providerType: "LLM",
    providerKey: "ollama",
    errorCode: "PROVIDER_TIMEOUT",
    message: "モデルの応答待ちでタイムアウトしました。",
    occurredAt: "2026-07-26T04:37:24Z",
  },
  {
    id: "oplog-2",
    level: "INFO",
    category: "PROVIDER_JOB",
    eventType: "provider.job.running",
    sourceId: "provider-job-2",
    correlationId: "corr-2",
    providerType: "TTS",
    providerKey: "voicevox",
    errorCode: null,
    message: "音声生成を開始しました。",
    occurredAt: "2026-07-26T04:38:24Z",
  },
];

describe("filterOperationalEvents", () => {
  it("レベルと検索文字列で失敗ログを絞り込む", () => {
    expect(filterOperationalEvents(EVENTS, "ERROR", "ALL", "ollama")).toEqual([EVENTS[0]]);
  });

  it("相関IDでも検索できる", () => {
    expect(filterOperationalEvents(EVENTS, "ALL", "ALL", "corr-2")).toEqual([EVENTS[1]]);
  });

  it("生成ジョブの状態とイベント種別を日本語化する", () => {
    expect(jobStatusLabel("SUCCEEDED")).toBe("成功");
    expect(jobStatusLabel("RUNNING")).toBe("実行中");
    expect(eventTypeLabel("provider.job.failed")).toBe("生成ジョブに失敗しました");
  });
});
