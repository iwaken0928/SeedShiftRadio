"use client";

import { create } from "zustand";
import { createJSONStorage, persist, type StateStorage } from "zustand/middleware";
import { createStore, type StateCreator } from "zustand/vanilla";
import type { StreamStatus } from "@/lib/sse";
import type { LetterSubmissionRecord } from "@/lib/types";

export type RouteKey = "radio" | "letters" | "settings" | "monitor";

export type EventEntry = {
  id: string | null;
  event: string;
  at: string;
  summary: string;
};

export type UiStore = {
  clientId: string;
  hasHydrated: boolean;
  selectedStationId: string | null;
  radioName: string;
  volume: number;
  activeRoute: RouteKey;
  connectionStatus: StreamStatus;
  liveSubtitle: string;
  lastEventId: string | null;
  recentEvents: EventEntry[];
  localLetterSubmissions: LetterSubmissionRecord[];
  ensureClientId: () => string;
  setHasHydrated: (hasHydrated: boolean) => void;
  setSelectedStationId: (stationId: string | null) => void;
  setRadioName: (radioName: string) => void;
  setVolume: (volume: number) => void;
  setActiveRoute: (route: RouteKey) => void;
  setConnectionStatus: (status: StreamStatus) => void;
  setLiveSubtitle: (subtitle: string) => void;
  pushEvent: (entry: EventEntry) => void;
  addLocalLetterSubmission: (entry: LetterSubmissionRecord) => void;
  setLastEventId: (eventId: string | null) => void;
  resetLiveState: () => void;
};

export type PersistedUiState = Pick<
  UiStore,
  "clientId" | "selectedStationId" | "radioName" | "volume" | "activeRoute" | "localLetterSubmissions"
>;

export const UI_STORE_STORAGE_KEY = "seedshift-radio-web-ui";

export function createClientId() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `web-${crypto.randomUUID()}`;
  }
  return `web-${Math.random().toString(36).slice(2, 12)}`;
}

export function clampVolume(volume: number) {
  return Math.min(1, Math.max(0, volume));
}

export function prependRecentEvent(recentEvents: EventEntry[], entry: EventEntry) {
  return [entry, ...recentEvents].slice(0, 30);
}

export function mergeLocalLetterSubmissions(
  localLetterSubmissions: LetterSubmissionRecord[],
  entry: LetterSubmissionRecord,
) {
  const next = [entry, ...localLetterSubmissions.filter((existing) => existing.id !== entry.id)];
  return next.slice(0, 20);
}

export function getPersistedUiState(state: UiStore): PersistedUiState {
  return {
    clientId: state.clientId,
    selectedStationId: state.selectedStationId,
    radioName: state.radioName,
    volume: state.volume,
    activeRoute: state.activeRoute,
    localLetterSubmissions: state.localLetterSubmissions,
  };
}

const createUiStoreState: StateCreator<UiStore, [], [], UiStore> = (set, get) => ({
  clientId: "",
  hasHydrated: false,
  selectedStationId: null,
  radioName: "Midnight Echo Listener",
  volume: 0.8,
  activeRoute: "radio",
  connectionStatus: "idle",
  liveSubtitle: "",
  lastEventId: null,
  recentEvents: [],
  localLetterSubmissions: [],
  ensureClientId: () => {
    const current = get().clientId;
    if (current) {
      return current;
    }
    const next = createClientId();
    set({ clientId: next });
    return next;
  },
  setHasHydrated: (hasHydrated) => set({ hasHydrated }),
  setSelectedStationId: (selectedStationId) => set({ selectedStationId }),
  setRadioName: (radioName) => set({ radioName }),
  setVolume: (volume) => set({ volume: clampVolume(volume) }),
  setActiveRoute: (activeRoute) => set({ activeRoute }),
  setConnectionStatus: (connectionStatus) => set({ connectionStatus }),
  setLiveSubtitle: (liveSubtitle) => set({ liveSubtitle }),
  pushEvent: (entry) =>
    set((state) => ({
      recentEvents: prependRecentEvent(state.recentEvents, entry),
    })),
  addLocalLetterSubmission: (entry) =>
    set((state) => ({
      localLetterSubmissions: mergeLocalLetterSubmissions(state.localLetterSubmissions, entry),
    })),
  setLastEventId: (lastEventId) => set({ lastEventId }),
  resetLiveState: () =>
    set({
      connectionStatus: "idle",
      liveSubtitle: "",
    }),
});

function createPersistedUiStore(storage?: StateStorage) {
  return persist(createUiStoreState, {
    name: UI_STORE_STORAGE_KEY,
    ...(storage ? { storage: createJSONStorage(() => storage) } : {}),
    partialize: getPersistedUiState,
    skipHydration: true,
    onRehydrateStorage: () => (state) => {
      state?.setHasHydrated(true);
    },
  });
}

export function createUiStoreStore(storage?: StateStorage) {
  return createStore<UiStore>()(createPersistedUiStore(storage));
}

export const useUiStore = create<UiStore>()(createPersistedUiStore());
