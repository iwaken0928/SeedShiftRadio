"use client";

import { useEffect } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getApiBase,
  getNextSpeechDirective,
  getRadioProgram,
  getRadioQueue,
  getRadioStatus,
  listStations,
  registerClientCapabilities,
  sendPlaybackEvent,
  startPlayback,
  stopPlayback,
  tuneRadio,
} from "@/lib/api";
import { AudioConsole } from "@/components/audio-console";
import { PanelColumn, PanelGrid } from "@/components/markdown";
import { Badge, Button, Card, EmptyState, Metric, SectionHeader } from "@/components/ui";
import { useUiStore } from "@/stores/ui-store";
import type { PlaybackEventRequest, QueueItem, RadioStatus, SpeechDirective, StationSummary } from "@/lib/types";

export function RadioDashboard() {
  const queryClient = useQueryClient();
  const selectedStationId = useUiStore((state) => state.selectedStationId);
  const setSelectedStationId = useUiStore((state) => state.setSelectedStationId);
  const volume = useUiStore((state) => state.volume);
  const setVolume = useUiStore((state) => state.setVolume);
  const ensureClientId = useUiStore((state) => state.ensureClientId);

  const stationsQuery = useQuery({
    queryKey: ["stations"],
    queryFn: listStations,
  });
  const statusQuery = useQuery({
    queryKey: ["radio", "status"],
    queryFn: getRadioStatus,
  });
  const queueQuery = useQuery({
    queryKey: ["radio", "queue"],
    queryFn: getRadioQueue,
  });
  const programQuery = useQuery({
    queryKey: ["radio", "program"],
    queryFn: getRadioProgram,
    retry: false,
  });
  const speechDirectiveQuery = useQuery({
    queryKey: ["radio", "speech-directive", ensureClientId()],
    queryFn: () => getNextSpeechDirective(ensureClientId()),
    enabled: Boolean(statusQuery.data?.sessionId),
    retry: false,
  });

  useEffect(() => {
    if (!selectedStationId && stationsQuery.data && stationsQuery.data.length > 0) {
      setSelectedStationId(stationsQuery.data[0].id);
    }
  }, [selectedStationId, setSelectedStationId, stationsQuery.data]);

  useEffect(() => {
    const clientId = ensureClientId();
    void registerClientCapabilities({
      clientId,
      clientType: "WEB",
      supportsClientSideTts: false,
      supportedVoiceEngines: [],
      preferredPlaybackMode: "SERVER_AUDIO",
      localVoiceProfiles: [],
    }).catch(() => undefined);
  }, [ensureClientId]);

  const tuneMutation = useMutation({
    mutationFn: () =>
      tuneRadio({
        stationId: selectedStationId ?? "",
        requestedBy: "web-client",
        resumePlayback: true,
      }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["radio"] }),
        queryClient.invalidateQueries({ queryKey: ["stations"] }),
      ]);
    },
  });

  const playMutation = useMutation({
    mutationFn: startPlayback,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["radio"] });
    },
  });

  const stopMutation = useMutation({
    mutationFn: stopPlayback,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["radio"] });
    },
  });

  const status = statusQuery.data;
  const queue = queueQuery.data;
  const currentOrNextItem = resolveCurrentOrNextItem(status, queue?.items ?? []);
  const stations = stationsQuery.data ?? [];

  return (
    <PanelGrid>
      <PanelColumn className="xl:col-span-7">
        <Card>
          <SectionHeader
            eyebrow="Radio"
            title="Live Playout"
            description="局選択、再生制御、現在の queue 状態をひとつの画面で追える初期 UI です。"
            action={
              <div className="flex flex-wrap gap-2">
                <Button
                  tone="secondary"
                  disabled={!selectedStationId || tuneMutation.isPending}
                  onClick={() => tuneMutation.mutate()}
                >
                  Tune
                </Button>
                <Button tone="primary" disabled={playMutation.isPending} onClick={() => playMutation.mutate()}>
                  Play
                </Button>
                <Button tone="ghost" disabled={stopMutation.isPending} onClick={() => stopMutation.mutate()}>
                  Stop
                </Button>
              </div>
            }
          />
          <div className="grid gap-3 md:grid-cols-4">
            <Metric label="State" value={status?.state ?? "IDLE"} tone={status?.degraded ? "warning" : "default"} />
            <Metric label="Session" value={status?.sessionId ?? "none"} />
            <Metric label="Buffer Ready" value={status?.bufferReadyCount ?? 0} tone="accent" />
            <Metric label="Current Item" value={status?.currentItemId ?? "none"} />
          </div>
          <div className="mt-5 grid gap-3 lg:grid-cols-[1.2fr_1fr]">
            <div className="space-y-3">
              <div>
                <label className="mb-2 block text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">
                  Station
                </label>
                <div className="flex flex-wrap gap-2">
                  {stations.length ? (
                    stations.map((station) => (
                      <button
                        key={station.id}
                        type="button"
                        onClick={() => setSelectedStationId(station.id)}
                        className={`rounded-full border px-4 py-2 text-sm font-semibold transition ${
                          selectedStationId === station.id
                            ? "border-slate-950 bg-slate-950 text-white"
                            : "border-slate-200 bg-white/80 text-slate-700 hover:border-teal-300 hover:text-slate-950"
                        }`}
                      >
                        {station.name}
                      </button>
                    ))
                  ) : (
                    <EmptyState title="局情報がありません" description="`/api/stations` の応答を待っています。" />
                  )}
                </div>
              </div>
              <div className="grid gap-3 md:grid-cols-2">
                <Card tone="accent" className="p-4">
                  <div className="text-xs font-semibold uppercase tracking-[0.2em] text-teal-700">Now playing</div>
                  <div className="mt-2 text-lg font-semibold text-slate-950">{currentOrNextItem?.title ?? "Queue waiting"}</div>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {currentOrNextItem ? <Badge tone="accent">{currentOrNextItem.type}</Badge> : null}
                    {currentOrNextItem ? <Badge tone="default">{currentOrNextItem.status}</Badge> : null}
                    {status?.programTitle ? <Badge tone="default">{status.programTitle}</Badge> : null}
                  </div>
                  {currentOrNextItem?.assetUrl && status?.sessionId ? (
                    <div className="mt-4">
                      <AudioConsole
                        sourceUrl={getAssetUrl(currentOrNextItem.assetUrl)}
                        label={currentOrNextItem.title}
                        clientId={ensureClientId()}
                        itemId={currentOrNextItem.id}
                        sessionId={status.sessionId}
                        volume={volume}
                        onPlaybackEvent={(request) => void emitPlaybackEvent(request)}
                      />
                    </div>
                  ) : (
                    <p className="mt-4 text-sm text-slate-600">assetUrl がまだないため、再生コントロールは待機中です。</p>
                  )}
                </Card>
                <Card className="p-4">
                  <div className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500">Client controls</div>
                  <div className="mt-3 space-y-3">
                    <div>
                      <label className="mb-2 block text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">
                        Volume
                      </label>
                      <input
                        type="range"
                        min="0"
                        max="1"
                        step="0.05"
                        value={volume}
                        onChange={(event) => setVolume(Number(event.target.value))}
                        className="w-full"
                      />
                    </div>
                    <div className="rounded-2xl bg-slate-50 px-3 py-3 text-sm text-slate-600">
                      clientId: <span className="font-mono text-slate-900">{ensureClientId()}</span>
                    </div>
                  </div>
                </Card>
              </div>
            </div>
            <Card className="p-4">
              <SectionHeader
                eyebrow="Speech"
                title="Next Speech Directive"
                description="Client-side TTS を使う将来 client 向けに、現在の `SpeechDirective` を見える化しています。"
              />
              {speechDirectiveQuery.data ? (
                <SpeechDirectivePanel directive={speechDirectiveQuery.data} />
              ) : (
                <EmptyState
                  title="SpeechDirective はまだありません"
                  description={speechDirectiveQuery.error instanceof Error ? speechDirectiveQuery.error.message : "queue 準備後に表示されます。"}
                />
              )}
            </Card>
          </div>
        </Card>

        <Card className="mt-4">
          <SectionHeader eyebrow="Queue" title="Queue Snapshot" description="SSE と REST で同期される queue の現在値です。" />
          {queue?.items?.length ? (
            <div className="grid gap-3">
              {queue.items.map((item) => (
                <div key={item.id} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <div className="font-semibold text-slate-950">{item.title}</div>
                    <Badge tone={item.status === "FAILED" ? "danger" : item.status === "READY" ? "success" : "default"}>
                      {item.status}
                    </Badge>
                    <Badge tone="default">{item.type}</Badge>
                    <Badge tone="accent">{item.slotRole}</Badge>
                  </div>
                  <div className="mt-2 text-sm text-slate-600">
                    {Math.round(item.durationMs / 1000)} sec
                    {item.assetBanned ? " / asset banned" : ""}
                    {item.assetUrl ? " / audio ready" : " / audio pending"}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title="Queue はまだ空です" description="Tune 後に warmup が走ると queue がここに表示されます。" />
          )}
        </Card>
      </PanelColumn>

      <PanelColumn className="xl:col-span-5">
        <Card>
          <SectionHeader eyebrow="Program" title="Current Program Block" description="現在 block と slot の解決結果です。" />
          {programQuery.data ? (
            <div className="space-y-3">
              <div className="flex flex-wrap gap-2">
                <Badge tone="accent">{programQuery.data.status}</Badge>
                <Badge tone="default">{programQuery.data.templateId ?? "no-template"}</Badge>
                <Badge tone="default">{programQuery.data.remainingSlotCount} slots remaining</Badge>
              </div>
              <h3 className="text-xl font-semibold tracking-tight text-slate-950">{programQuery.data.title}</h3>
              <div className="grid gap-2">
                {programQuery.data.slots.map((slot) => (
                  <div key={slot.id} className="rounded-2xl border border-slate-200 bg-slate-50 px-3 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <div className="font-semibold text-slate-900">{slot.title}</div>
                      <Badge tone="default">{slot.role}</Badge>
                      <Badge tone={slot.constraintMode === "HARD" ? "danger" : "accent"}>{slot.constraintMode}</Badge>
                    </div>
                    <div className="mt-1 text-sm text-slate-600">
                      {slot.resolvedSegmentType} / {Math.round(slot.targetDurationMs / 1000)} sec / {slot.status}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : (
            <EmptyState
              title="番組 block はまだありません"
              description={programQuery.error instanceof Error ? programQuery.error.message : "Tune 後に block が見えるようになります。"}
            />
          )}
        </Card>
      </PanelColumn>
    </PanelGrid>
  );
}

function resolveCurrentOrNextItem(status: RadioStatus | undefined, items: QueueItem[]) {
  if (!status) {
    return null;
  }
  return items.find((item) => item.id === status.currentItemId) ?? items.find((item) => item.status === "READY") ?? null;
}

function getAssetUrl(assetUrl: string) {
  return `${getApiBase()}${assetUrl}`;
}

async function emitPlaybackEvent(request: PlaybackEventRequest) {
  if (!request.clientId || !request.sessionId || !request.itemId || !request.occurredAt) {
    return;
  }
  await sendPlaybackEvent(request);
}

function SpeechDirectivePanel({ directive }: { directive: SpeechDirective }) {
  return (
    <div className="space-y-3">
      <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
        <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Text</div>
        <p className="mt-2 text-sm leading-7 text-slate-700">{directive.text}</p>
      </div>
      <div className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3 text-sm text-slate-700">
        <div className="font-semibold text-slate-900">normalizedText</div>
        <div className="mt-2">{directive.normalizedText}</div>
      </div>
      <div className="flex flex-wrap gap-2">
        <Badge tone="accent">{directive.emotion}</Badge>
        <Badge tone="default">{directive.tempo}</Badge>
        {directive.voiceHint ? <Badge tone="default">{directive.voiceHint}</Badge> : null}
      </div>
    </div>
  );
}
