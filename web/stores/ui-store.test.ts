import { describe, expect, it, vi } from "vitest";
import type { LetterSubmissionRecord } from "@/lib/types";
import { createUiStoreStore, UI_STORE_STORAGE_KEY } from "@/stores/ui-store";

function createMemoryStorage(initialEntries?: Record<string, string>) {
  const storage = new Map(Object.entries(initialEntries ?? {}));

  return {
    getItem: (name: string) => storage.get(name) ?? null,
    setItem: (name: string, value: string) => {
      storage.set(name, value);
    },
    removeItem: (name: string) => {
      storage.delete(name);
    },
  };
}

function createSubmission(id: string): LetterSubmissionRecord {
  return {
    id,
    stationId: "station-a",
    radioName: `radio-${id}`,
    subject: `subject-${id}`,
    status: "PENDING",
    createdAt: "2026-04-21T00:00:00Z",
  };
}

describe("ui-store", () => {
  it("volume を 0 から 1 の範囲に丸める", () => {
    const store = createUiStoreStore(createMemoryStorage());

    store.getState().setVolume(1.5);
    expect(store.getState().volume).toBe(1);

    store.getState().setVolume(-0.5);
    expect(store.getState().volume).toBe(0);
  });

  it("recentEvents を先頭追加しつつ 30 件に制限する", () => {
    const store = createUiStoreStore(createMemoryStorage());

    for (let index = 0; index < 35; index += 1) {
      store.getState().pushEvent({
        id: String(index),
        event: "queue.updated",
        at: `2026-04-21T00:00:${String(index).padStart(2, "0")}Z`,
        summary: `event-${index}`,
      });
    }

    const events = store.getState().recentEvents;
    expect(events).toHaveLength(30);
    expect(events[0]?.id).toBe("34");
    expect(events.at(-1)?.id).toBe("5");
  });

  it("localLetterSubmissions を id で upsert し 20 件に制限する", () => {
    const store = createUiStoreStore(createMemoryStorage());

    for (let index = 0; index < 21; index += 1) {
      store.getState().addLocalLetterSubmission(createSubmission(`letter-${index}`));
    }
    store.getState().addLocalLetterSubmission({
      ...createSubmission("letter-5"),
      radioName: "updated-radio",
    });

    const submissions = store.getState().localLetterSubmissions;
    expect(submissions).toHaveLength(20);
    expect(submissions[0]).toMatchObject({ id: "letter-5", radioName: "updated-radio" });
    expect(submissions.some((entry) => entry.id === "letter-0")).toBe(false);
  });

  it("persist 対象だけを storage に保存する", () => {
    const storage = createMemoryStorage();
    const store = createUiStoreStore(storage);

    store.getState().setRadioName("Night Shift");
    store.getState().setVolume(0.25);
    store.getState().setConnectionStatus("connected");
    store.getState().setLiveSubtitle("on air");
    store.getState().setLastEventId("evt-1");
    store.getState().pushEvent({
      id: "evt-1",
      event: "subtitle.updated",
      at: "2026-04-21T00:01:00Z",
      summary: "subtitle changed",
    });
    store.getState().addLocalLetterSubmission(createSubmission("letter-1"));

    const persisted = JSON.parse(storage.getItem(UI_STORE_STORAGE_KEY) ?? "null") as {
      state: Record<string, unknown>;
    } | null;

    expect(persisted?.state).toMatchObject({
      radioName: "Night Shift",
      volume: 0.25,
      localLetterSubmissions: [expect.objectContaining({ id: "letter-1" })],
    });
    expect(persisted?.state.connectionStatus).toBeUndefined();
    expect(persisted?.state.liveSubtitle).toBeUndefined();
    expect(persisted?.state.recentEvents).toBeUndefined();
    expect(persisted?.state.lastEventId).toBeUndefined();
  });

  it("clientId が空なら ensureClientId で再生成する", () => {
    const randomUuidSpy = vi.spyOn(globalThis.crypto, "randomUUID").mockReturnValue("fixed-id");
    const store = createUiStoreStore(createMemoryStorage());

    expect(store.getState().ensureClientId()).toBe("web-fixed-id");
    expect(store.getState().clientId).toBe("web-fixed-id");
    expect(randomUuidSpy).toHaveBeenCalledOnce();
  });

  it("初期描画では storage を同期反映せず、明示的な rehydrate 後に復元する", async () => {
    const storage = createMemoryStorage({
      [UI_STORE_STORAGE_KEY]: JSON.stringify({
        state: {
          clientId: "web-persisted",
          selectedStationId: "station-persisted",
          radioName: "Persisted Listener",
          volume: 0.4,
          activeRoute: "radio",
          localLetterSubmissions: [],
        },
        version: 0,
      }),
    });
    const store = createUiStoreStore(storage);

    expect(store.getState().clientId).toBe("");
    expect(store.getState().selectedStationId).toBeNull();

    await store.persist.rehydrate();

    expect(store.getState().hasHydrated).toBe(true);
    expect(store.getState().clientId).toBe("web-persisted");
    expect(store.getState().selectedStationId).toBe("station-persisted");
  });
});
