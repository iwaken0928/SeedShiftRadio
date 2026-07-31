import { describe, expect, it } from "vitest";

import { buildQueueItem, buildRadioStatus } from "../e2e/fixtures";
import { resolveCurrentOrNextProgramItem } from "@/lib/radio-playback";
import type { QueueItem, RadioStatus } from "@/lib/types";

describe("resolveCurrentOrNextProgramItem", () => {
  it("同一番組の先頭項目が未準備なら後続や次番組を選ばない", () => {
    const blockedOpening = buildQueueItem({ id: "opening", status: "GENERATING" });
    const laterReady = buildQueueItem({ id: "topic", programSlotId: "topic-slot", status: "READY" });
    const nextProgramReady = buildQueueItem({
      id: "next-program",
      programBlockId: "block-night-002",
      programSlotId: "next-opening-slot",
      status: "READY",
    });

    expect(resolveCurrentOrNextProgramItem(
      buildRadioStatus({ programBlockId: "block-night-001", currentItemId: null }) as RadioStatus,
      [blockedOpening, laterReady, nextProgramReady] as QueueItem[],
    )).toBeNull();
  });

  it("現在の番組の先頭READY項目だけを返す", () => {
    const currentReady = buildQueueItem({ id: "opening", status: "READY" });
    const nextProgramReady = buildQueueItem({
      id: "next-program",
      programBlockId: "block-night-002",
      programSlotId: "next-opening-slot",
      status: "READY",
    });

    expect(resolveCurrentOrNextProgramItem(
      buildRadioStatus({ programBlockId: "block-night-001", currentItemId: null }) as RadioStatus,
      [currentReady, nextProgramReady] as QueueItem[],
    )?.id).toBe(currentReady.id);
  });

  it("queue の PLAYING 更新が status.currentItemId より先でも再生対象を維持する", () => {
    const completedOpening = buildQueueItem({ id: "opening", status: "DONE" });
    const playingMusic = buildQueueItem({
      id: "music",
      programSlotId: "music-slot",
      status: "PLAYING",
    });

    expect(resolveCurrentOrNextProgramItem(
      buildRadioStatus({ programBlockId: "block-night-001", currentItemId: null }) as RadioStatus,
      [completedOpening, playingMusic] as QueueItem[],
    )?.id).toBe(playingMusic.id);
  });
});
