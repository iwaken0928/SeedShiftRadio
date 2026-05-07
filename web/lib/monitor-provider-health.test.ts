import { describe, expect, it } from "vitest";
import type { ProviderHealthPayload } from "@/lib/types";
import { extractWorkerStatusDetails, getProviderMetadataHighlights } from "@/lib/monitor-provider-health";

function createHealth(metadata?: Record<string, unknown> | null): ProviderHealthPayload {
  return {
    providerType: "MUSIC",
    providerKey: "musicgen",
    status: "UP",
    lastCheckedAt: "2026-04-21T00:00:00Z",
    responseTimeMs: 120,
    message: "ok",
    capabilities: ["GENERATE"],
    baseUrl: "http://127.0.0.1:9000",
    metadata,
  };
}

describe("monitor-provider-health", () => {
  it("worker metadata を details へ正規化する", () => {
    const details = extractWorkerStatusDetails(
      createHealth({
        adapter: "http",
        defaultModelProfileId: "profile-default",
        modelProfileIds: ["profile-default", { id: "profile-alt" }, 42, true, {}],
        queueSize: "3",
        queuedJobs: 2,
        runningJobs: "1",
        averageJobSeconds: "7.5",
        defaultModel: { model: "musicgen-medium" },
        models: ["musicgen-medium", { name: "musicgen-large" }, false, {}],
        statsStatus: "UP",
        modelsStatus: "SYNCED",
      }),
    );

    expect(details).toEqual({
      adapter: "http",
      defaultModelProfileId: "profile-default",
      modelProfileIds: ["profile-default", "profile-alt", "42", "true"],
      queueSize: 3,
      queuedJobs: 2,
      runningJobs: 1,
      averageJobSeconds: 7.5,
      defaultModel: "musicgen-medium",
      models: ["musicgen-medium", "musicgen-large", "false"],
      statsStatus: "UP",
      modelsStatus: "SYNCED",
    });
  });

  it("worker 関連 metadata がない場合は null を返す", () => {
    expect(extractWorkerStatusDetails(createHealth({ foo: "bar" }))).toBeNull();
    expect(extractWorkerStatusDetails(createHealth(null))).toBeNull();
  });

  it("highlight は主要な worker status だけを表示用に抜き出す", () => {
    const highlights = getProviderMetadataHighlights(
      createHealth({
        adapter: "worker-http",
        queueSize: "4",
        runningJobs: 2,
        defaultModelProfileId: "profile-a",
      }),
    );

    expect(highlights).toEqual([
      { label: "Adapter", value: "worker-http" },
      { label: "Queue", value: "4" },
      { label: "Running", value: "2" },
      { label: "Profile", value: "profile-a" },
    ]);
  });

  it("worker metadata の nested object に secret や本文系 key が混ざる場合は表示値にしない", () => {
    const details = extractWorkerStatusDetails(
      createHealth({
        adapter: "worker-http",
        defaultModel: { model: "musicgen-medium", apiKey: "sk-secret" },
        models: [{ name: "musicgen-large", prompt: "raw prompt" }, { name: "musicgen-small" }],
        modelProfileIds: [{ id: "profile-safe", radioName: "secret radio" }, { id: "profile-visible" }],
      }),
    );

    expect(details?.defaultModel).toBeNull();
    expect(details?.models).toEqual(["musicgen-small"]);
    expect(details?.modelProfileIds).toEqual(["profile-visible"]);
  });

  it("worker metadata の object fallback は JSON 表示しない", () => {
    const details = extractWorkerStatusDetails(
      createHealth({
        adapter: "worker-http",
        models: [{ unexpected: "value" }],
      }),
    );

    expect(details?.models).toEqual([]);
  });
});
