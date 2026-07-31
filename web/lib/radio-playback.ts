import type { QueueItem, RadioStatus } from "@/lib/types";

export function resolveCurrentOrNextProgramItem(status: RadioStatus | undefined, items: QueueItem[]) {
  if (!status) {
    return null;
  }
  const currentItem = items.find((item) => item.id === status.currentItemId);
  if (currentItem) {
    return currentItem;
  }
  const programItems = status.programBlockId
    ? items.filter((item) => item.programBlockId === status.programBlockId)
    : items;
  // status と queue は別 query / SSE event で更新されるため、queue 側だけが先に
  // PLAYING へ進む瞬間がある。ここで null を返すと再生中の audio 要素が
  // unmount され、cleanup が PLAYBACK_STOPPED を送ってしまう。
  const playingItem = programItems.find((item) => item.status === "PLAYING");
  if (playingItem) {
    return playingItem;
  }
  const firstRemainingItem = programItems.find((item) => item.status !== "DONE" && item.status !== "FAILED");
  return firstRemainingItem?.status === "READY" ? firstRemainingItem : null;
}
