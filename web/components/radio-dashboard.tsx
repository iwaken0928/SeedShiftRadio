"use client";

import { useEffect, useRef } from "react";
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
import { AudioConsole, type AudioConsoleHandle } from "@/components/audio-console";
import { PanelColumn, PanelGrid } from "@/components/markdown";
import { Badge, Button, Card, EmptyState, Metric, SectionHeader } from "@/components/ui";
import { useUiStore } from "@/stores/ui-store";
import type { PlaybackEventRequest, QueueItem, RadioStatus, SpeechDirective, StationSummary } from "@/lib/types";

export function RadioDashboard() {
  const queryClient = useQueryClient();
  const audioConsoleRef = useRef<AudioConsoleHandle | null>(null);
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
    refetchInterval: 3_000,
    refetchOnWindowFocus: false,
  });
  const queueQuery = useQuery({
    queryKey: ["radio", "queue"],
    queryFn: getRadioQueue,
    enabled: Boolean(statusQuery.data?.sessionId),
    retry: false,
    refetchInterval: 3_000,
    refetchOnWindowFocus: false,
  });
  const programQuery = useQuery({
    queryKey: ["radio", "program"],
    queryFn: getRadioProgram,
    enabled: Boolean(statusQuery.data?.programBlockId),
    retry: false,
    refetchInterval: 5_000,
    refetchOnWindowFocus: false,
  });
  const speechDirectiveQuery = useQuery({
    queryKey: ["radio", "speech-directive", ensureClientId()],
    queryFn: () => getNextSpeechDirective(ensureClientId()),
    enabled: Boolean(
      statusQuery.data?.sessionId
      && queueQuery.data?.items.some((item) => item.status === "READY" || item.status === "PLAYING"),
    ),
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
    mutationFn: async () => {
      const status = await startPlayback();
      await audioConsoleRef.current?.play();
      return status;
    },
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
        <Card elevation="raised" material="glass" motion="enter">
          <SectionHeader
            eyebrow="ラジオ"
            title="番組再生"
            description="局を選んで準備を開始し、再生可能になったら同じ画面から音声を再生できます。"
            action={
              <div className="flex flex-wrap gap-2">
                <Button
                  tone="secondary"
                  disabled={!selectedStationId || tuneMutation.isPending}
                  onClick={() => tuneMutation.mutate()}
                  data-testid="radio-tune"
                >
                  選局・準備
                </Button>
                <Button
                  tone="primary"
                  disabled={playMutation.isPending || !currentOrNextItem?.assetUrl}
                  onClick={() => playMutation.mutate()}
                  data-testid="radio-play"
                >
                  {playMutation.isPending ? "再生を開始中…" : "音声を再生"}
                </Button>
                <Button tone="ghost" disabled={stopMutation.isPending} onClick={() => stopMutation.mutate()} data-testid="radio-stop">
                  停止
                </Button>
              </div>
            }
          />
          <div className="grid gap-3 md:grid-cols-4">
            <div data-testid="radio-state">
              <Metric label="State" value={status?.state ?? "IDLE"} tone={status?.degraded ? "warning" : "default"} />
            </div>
            <Metric label="Session" value={status?.sessionId ?? "none"} />
            <Metric label="Buffer Ready" value={status?.bufferReadyCount ?? 0} tone="accent" />
            <Metric label="Current Item" value={status?.currentItemId ?? "none"} />
          </div>
          {playMutation.isError ? (
            <p className="mt-3 text-sm font-medium text-rose-700" role="alert">
              {playMutation.error instanceof Error ? playMutation.error.message : "再生を開始できませんでした。"}
            </p>
          ) : null}
          <div className="mt-5 grid gap-3 lg:grid-cols-[1.2fr_1fr]">
            <div className="space-y-3">
              <div>
                <label className="mb-2 block text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">
                  局
                </label>
                <div className="flex flex-wrap gap-2">
                  {stations.length ? (
                    stations.map((station) => (
                      <button
                        key={station.id}
                        type="button"
                        onClick={() => setSelectedStationId(station.id)}
                        data-testid={`station-option-${station.id}`}
                        className={`interactive-control rounded-full border px-4 py-2 text-sm font-semibold ${
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
                  <div className="text-xs font-semibold uppercase tracking-[0.2em] text-teal-700">現在／次のセグメント</div>
                  <div className="mt-2 text-lg font-semibold text-slate-950" data-testid="radio-now-playing-title">
                    {currentOrNextItem?.title ?? "生成・準備を待っています"}
                  </div>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {currentOrNextItem ? <Badge tone="accent">{currentOrNextItem.type}</Badge> : null}
                    {currentOrNextItem ? <Badge tone="default">{currentOrNextItem.status}</Badge> : null}
                    {status?.programTitle ? <Badge tone="default">{status.programTitle}</Badge> : null}
                  </div>
                  {currentOrNextItem?.assetUrl && status?.sessionId ? (
                    <div className="mt-4">
                      <AudioConsole
                        ref={audioConsoleRef}
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
                  <div className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500">再生設定</div>
                  <div className="mt-3 space-y-3">
                    <div>
                      <label className="mb-2 block text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">
                        音量
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
                title="次の読み上げ指示"
                description="再生可能なセグメントが用意された時だけ、読み上げ指示を取得します。"
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
          <SectionHeader eyebrow="キュー" title="再生待ち一覧" description="SSE に加えて3秒間隔の REST 再同期で、生成完了後の状態を追跡します。" />
          {queue?.items?.length ? (
            <div className="grid gap-3" data-testid="queue-list">
              {queue.items.map((item) => (
                <div
                  key={item.id}
                  className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3"
                  data-testid="queue-item"
                  data-itemid={item.id}
                >
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
          <SectionHeader eyebrow="番組" title="現在の番組構成" description="現在の番組と各枠の準備状態です。" />
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
