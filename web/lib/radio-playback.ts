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
  const firstRemainingItem = programItems.find((item) => item.status !== "DONE" && item.status !== "FAILED");
  return firstRemainingItem?.status === "READY" ? firstRemainingItem : null;
}
