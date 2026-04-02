"use client";

import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { StreamStatus } from "@/lib/sse";

type RouteKey = "radio" | "letters" | "settings" | "monitor";

type EventEntry = {
  id: string | null;
  event: string;
  at: string;
  summary: string;
};

type UiStore = {
  clientId: string;
  selectedStationId: string | null;
  radioName: string;
  volume: number;
  activeRoute: RouteKey;
  connectionStatus: StreamStatus;
  liveSubtitle: string;
  lastEventId: string | null;
  recentEvents: EventEntry[];
  ensureClientId: () => string;
  setSelectedStationId: (stationId: string | null) => void;
  setRadioName: (radioName: string) => void;
  setVolume: (volume: number) => void;
  setActiveRoute: (route: RouteKey) => void;
  setConnectionStatus: (status: StreamStatus) => void;
  setLiveSubtitle: (subtitle: string) => void;
  pushEvent: (entry: EventEntry) => void;
  setLastEventId: (eventId: string | null) => void;
  resetLiveState: () => void;
};

function createClientId() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `web-${crypto.randomUUID()}`;
  }
  return `web-${Math.random().toString(36).slice(2, 12)}`;
}

export const useUiStore = create<UiStore>()(
  persist(
    (set, get) => ({
      clientId: createClientId(),
      selectedStationId: null,
      radioName: "Midnight Echo Listener",
      volume: 0.8,
      activeRoute: "radio",
      connectionStatus: "idle",
      liveSubtitle: "",
      lastEventId: null,
      recentEvents: [],
      ensureClientId: () => {
        const current = get().clientId;
        if (current) {
          return current;
        }
        const next = createClientId();
        set({ clientId: next });
        return next;
      },
      setSelectedStationId: (selectedStationId) => set({ selectedStationId }),
      setRadioName: (radioName) => set({ radioName }),
      setVolume: (volume) => set({ volume: Math.min(1, Math.max(0, volume)) }),
      setActiveRoute: (activeRoute) => set({ activeRoute }),
      setConnectionStatus: (connectionStatus) => set({ connectionStatus }),
      setLiveSubtitle: (liveSubtitle) => set({ liveSubtitle }),
      pushEvent: (entry) =>
        set((state) => ({
          recentEvents: [entry, ...state.recentEvents].slice(0, 30),
        })),
      setLastEventId: (lastEventId) => set({ lastEventId }),
      resetLiveState: () =>
        set({
          connectionStatus: "idle",
          liveSubtitle: "",
        }),
    }),
    {
      name: "seedshift-radio-web-ui",
      partialize: (state) => ({
        clientId: state.clientId,
        selectedStationId: state.selectedStationId,
        radioName: state.radioName,
        volume: state.volume,
        activeRoute: state.activeRoute,
      }),
    },
  ),
);
