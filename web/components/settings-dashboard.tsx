"use client";

import { useEffect, useState, type Dispatch, type ReactNode, type SetStateAction } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getProgramTemplate,
  getSettings,
  getStation,
  getStationProgramming,
  listProgramTemplates,
  listStations,
  previewProgramming,
  testConnections,
  updateSettings,
} from "@/lib/api";
import { getAdminToken } from "@/lib/env";
import type {
  ConnectionsTestResponse,
  ProgramTemplateDetail,
  ProgramTemplateSummary,
  ProgrammingPreviewRequest,
  ProgrammingPreviewResponse,
  FeatureSettings,
  ProviderCatalog,
  ProviderGroup,
  ProviderHealthPayload,
  SettingsResponse,
  SettingsUpdateRequest,
  StationDetail,
  StationProgrammingResponse,
  StationSummary,
} from "@/lib/types";
import { Badge, Button, Card, EmptyState, Input, Label, Metric, SectionHeader } from "@/components/ui";
import { PanelColumn, PanelGrid } from "@/components/markdown";
import { useUiStore } from "@/stores/ui-store";

const REUSE_SCOPE_OPTIONS = ["DISABLED", "SESSION", "STATION", "GLOBAL", "ARCHIVE_ONLY"] as const;
const PROVIDER_GROUPS: Array<keyof ProviderCatalog> = ["llm", "tts", "musicGen"];
const PROVIDER_LABELS: Record<keyof ProviderCatalog, string> = {
  llm: "LLM",
  tts: "TTS",
  musicGen: "MusicGen",
};
const PROGRAMMING_STATE_OPTIONS = ["UP", "DEGRADED", "DOWN", "UNKNOWN"] as const;
const SELECT_CLASS_NAME =
  "w-full rounded-2xl border border-slate-300 bg-white/85 px-4 py-3 text-sm text-slate-900 outline-none transition focus:border-teal-500 focus:ring-2 focus:ring-teal-200";

export function SettingsDashboard() {
  const queryClient = useQueryClient();
  const hasAdminToken = Boolean(getAdminToken());
  const selectedStationId = useUiStore((state) => state.selectedStationId);
  const setSelectedStationId = useUiStore((state) => state.setSelectedStationId);
  const [draft, setDraft] = useState<SettingsUpdateRequest | null>(null);
  const [saveNotice, setSaveNotice] = useState<string | null>(null);
  const [selectedTemplateId, setSelectedTemplateId] = useState<string | null>(null);
  const [previewDraft, setPreviewDraft] = useState(createPreviewDraft);

  const settingsQuery = useQuery({
    queryKey: ["settings"],
    queryFn: getSettings,
    enabled: hasAdminToken,
    retry: false,
    refetchOnWindowFocus: false,
  });

  useEffect(() => {
    if (!settingsQuery.data) {
      return;
    }
    setDraft(createDraft(settingsQuery.data));
  }, [settingsQuery.data]);

  const saveMutation = useMutation({
    mutationFn: updateSettings,
    onMutate: () => {
      setSaveNotice(null);
    },
    onSuccess: (saved) => {
      queryClient.setQueryData(["settings"], saved);
      setDraft(createDraft(saved));
      setSaveNotice("設定を保存しました。接続テストは保存済みの内容に対して実行されます。");
    },
  });

  const connectionsMutation = useMutation({
    mutationFn: testConnections,
  });

  const stationsQuery = useQuery({
    queryKey: ["settings", "stations"],
    queryFn: listStations,
    enabled: hasAdminToken,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const stationDetailQuery = useQuery({
    queryKey: ["settings", "station", selectedStationId],
    queryFn: () => getStation(selectedStationId ?? ""),
    enabled: hasAdminToken && Boolean(selectedStationId),
    retry: false,
    refetchOnWindowFocus: false,
  });
  const stationProgrammingQuery = useQuery({
    queryKey: ["settings", "station-programming", selectedStationId],
    queryFn: () => getStationProgramming(selectedStationId ?? ""),
    enabled: hasAdminToken && Boolean(selectedStationId),
    retry: false,
    refetchOnWindowFocus: false,
  });
  const templatesQuery = useQuery({
    queryKey: ["settings", "program-templates"],
    queryFn: listProgramTemplates,
    enabled: hasAdminToken,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const templateDetailQuery = useQuery({
    queryKey: ["settings", "program-template", selectedTemplateId],
    queryFn: () => getProgramTemplate(selectedTemplateId ?? ""),
    enabled: hasAdminToken && Boolean(selectedTemplateId),
    retry: false,
    refetchOnWindowFocus: false,
  });
  const previewMutation = useMutation({
    mutationFn: () =>
      previewProgramming(selectedStationId ?? "", {
        at: toPreviewIso(previewDraft.at),
        pendingLetterCount: previewDraft.pendingLetterCount,
        providerStates: previewDraft.providerStates,
      }),
  });

  const baseDraft = settingsQuery.data ? createDraft(settingsQuery.data) : null;
  const isDirty = baseDraft !== null && draft !== null && JSON.stringify(baseDraft) !== JSON.stringify(draft);
  const bindHostWarning = draft && isUnsafeBindHost(draft.server.bindHost);

  const resetDraft = () => {
    if (!settingsQuery.data) {
      return;
    }
    setDraft(createDraft(settingsQuery.data));
    setSaveNotice("未保存の変更を破棄しました。");
  };

  const updateDraft = (updater: (current: SettingsUpdateRequest) => SettingsUpdateRequest) => {
    setDraft((current) => (current ? updater(current) : current));
    setSaveNotice(null);
  };

  useEffect(() => {
    if (!stationsQuery.data?.length) {
      return;
    }
    if (!selectedStationId || !stationsQuery.data.some((station) => station.id === selectedStationId)) {
      setSelectedStationId(stationsQuery.data[0].id);
    }
  }, [selectedStationId, setSelectedStationId, stationsQuery.data]);

  useEffect(() => {
    if (!templatesQuery.data?.length) {
      setSelectedTemplateId(null);
      return;
    }
    if (selectedTemplateId && templatesQuery.data.some((template) => template.id === selectedTemplateId)) {
      return;
    }
    const preferredTemplate =
      (selectedStationId ? templatesQuery.data.find((template) => template.stationId === selectedStationId) : undefined) ??
      templatesQuery.data[0];
    setSelectedTemplateId(preferredTemplate.id);
  }, [selectedStationId, selectedTemplateId, templatesQuery.data]);

  if (!hasAdminToken) {
    return (
      <PanelGrid>
        <PanelColumn className="xl:col-span-7">
          <Card>
            <SectionHeader
              eyebrow="Settings"
              title="Admin token required"
              description="`/settings` は管理トークン前提の画面です。公開 UI には表示せず、直接アクセスされた場合だけ案内を出します。"
            />
            <EmptyState
              title="設定画面は管理トークンが必要です"
              description="`NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN` または `NEXT_PUBLIC_ADMIN_TOKEN` を設定してから開いてください。"
            />
          </Card>
        </PanelColumn>
      </PanelGrid>
    );
  }

  return (
    <PanelGrid>
      <PanelColumn className="xl:col-span-7">
        <Card>
          <SectionHeader
            eyebrow="Settings"
            title="Runtime configuration"
            description="設計書どおりの `/api/settings` 契約に合わせて、runtime 設定を一括編集できる画面です。playout と programming の変更は、実行中 block ではなく次の番組から反映されます。"
            action={
              <div className="flex flex-wrap items-center gap-2">
                <Button tone="secondary" onClick={() => connectionsMutation.mutate()} disabled={connectionsMutation.isPending}>
                  {connectionsMutation.isPending ? "Testing..." : "Test Connections"}
                </Button>
                <Button tone="ghost" onClick={resetDraft} disabled={!isDirty || saveMutation.isPending}>
                  Reset
                </Button>
                <Button tone="primary" onClick={() => draft && saveMutation.mutate(draft)} disabled={!draft || !isDirty || saveMutation.isPending}>
                  {saveMutation.isPending ? "Saving..." : "Save Settings"}
                </Button>
              </div>
            }
          />

          {settingsQuery.data && draft ? (
            <div className="space-y-4">
              <div className="grid gap-3 md:grid-cols-4">
                <Metric label="Version" value={settingsQuery.data.version} />
                <Metric label="Schema" value={settingsQuery.data.schemaVersion} />
                <Metric label="Bind" value={`${draft.server.bindHost}:${draft.server.port}`} tone={bindHostWarning ? "warning" : "default"} />
                <Metric label="Updated" value={formatTimestamp(settingsQuery.data.updatedAt)} />
              </div>
              <Metric label="Config Path" value={settingsQuery.data.configPath} />

              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={isDirty ? "warning" : "success"}>{isDirty ? "Unsaved changes" : "Saved state"}</Badge>
                <Badge tone="accent">Playout / Programming は次の番組から反映</Badge>
              </div>

              {saveNotice ? <InlineNotice tone="accent" message={saveNotice} /> : null}
              {saveMutation.error instanceof Error ? <InlineNotice tone="danger" message={saveMutation.error.message} /> : null}
              {bindHostWarning ? (
                <InlineNotice
                  tone="warning"
                  message="`bindHost` が localhost 以外です。LAN へ公開される可能性があるため、管理トークン設定とネットワーク制御を併せて確認してください。"
                />
              ) : null}

              <SettingsSection
                title="Server / Paths"
                description="bind host の既定は `127.0.0.1` です。musicLibrary は dataRoot 配下に置く必要があります。"
              >
                <div className="grid gap-4 md:grid-cols-2">
                  <TextField
                    id="bindHost"
                    label="Bind Host"
                    value={draft.server.bindHost}
                    onChange={(value) => updateDraft((current) => ({ ...current, server: { ...current.server, bindHost: value } }))}
                  />
                  <NumberField
                    id="serverPort"
                    label="Port"
                    value={draft.server.port}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, server: { ...current.server, port: value } }))}
                  />
                  <TextField
                    id="dataRoot"
                    label="Data Root"
                    value={draft.paths.dataRoot}
                    onChange={(value) => updateDraft((current) => ({ ...current, paths: { ...current.paths, dataRoot: value } }))}
                  />
                  <TextField
                    id="musicLibrary"
                    label="Music Library"
                    value={draft.paths.musicLibrary}
                    onChange={(value) => updateDraft((current) => ({ ...current, paths: { ...current.paths, musicLibrary: value } }))}
                  />
                </div>
              </SettingsSection>

              <SettingsSection
                title="Playout"
                description="queue warmup/refill の上限と ahead count を編集します。番組の途中ではなく、次の block から反映される前提です。"
              >
                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                  <NumberField
                    id="targetReadyCount"
                    label="Target Ready Count"
                    value={draft.playout.targetReadyCount}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, targetReadyCount: value } }))}
                  />
                  <NumberField
                    id="minimumReadyCount"
                    label="Minimum Ready Count"
                    value={draft.playout.minimumReadyCount}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, minimumReadyCount: value } }))}
                  />
                  <NumberField
                    id="minReadyDurationMs"
                    label="Min Ready Duration (ms)"
                    value={draft.playout.minReadyDurationMs}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, minReadyDurationMs: value } }))}
                  />
                  <NumberField
                    id="maxPreparedDurationMs"
                    label="Max Prepared Duration (ms)"
                    value={draft.playout.maxPreparedDurationMs}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, maxPreparedDurationMs: value } }))}
                  />
                  <NumberField
                    id="maxPreparedBlocks"
                    label="Max Prepared Blocks"
                    value={draft.playout.maxPreparedBlocks}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, maxPreparedBlocks: value } }))}
                  />
                  <NumberField
                    id="scriptAheadCount"
                    label="Script Ahead Count"
                    value={draft.playout.scriptAheadCount}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, scriptAheadCount: value } }))}
                  />
                  <NumberField
                    id="ttsAheadCount"
                    label="TTS Ahead Count"
                    value={draft.playout.ttsAheadCount}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, ttsAheadCount: value } }))}
                  />
                  <NumberField
                    id="musicAheadCount"
                    label="Music Ahead Count"
                    value={draft.playout.musicAheadCount}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, musicAheadCount: value } }))}
                  />
                  <CheckboxField
                    id="idlePrefetchEnabled"
                    label="Idle Prefetch Enabled"
                    checked={draft.playout.idlePrefetchEnabled}
                    description="待機中にも先読みを許可します。"
                    onChange={(checked) => updateDraft((current) => ({ ...current, playout: { ...current.playout, idlePrefetchEnabled: checked } }))}
                  />
                </div>
              </SettingsSection>

              <SettingsSection title="Cache" description="script / TTS / music の保持量、日数、再利用範囲をまとめて調整します。">
                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                  <NumberField
                    id="scriptMaxBytes"
                    label="Script Max Bytes"
                    value={draft.cache.scriptMaxBytes}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, scriptMaxBytes: value } }))}
                  />
                  <NumberField
                    id="ttsMaxBytes"
                    label="TTS Max Bytes"
                    value={draft.cache.ttsMaxBytes}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, ttsMaxBytes: value } }))}
                  />
                  <NumberField
                    id="musicMaxBytes"
                    label="Music Max Bytes"
                    value={draft.cache.musicMaxBytes}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, musicMaxBytes: value } }))}
                  />
                  <NumberField
                    id="scriptRetentionDays"
                    label="Script Retention Days"
                    value={draft.cache.scriptRetentionDays}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, scriptRetentionDays: value } }))}
                  />
                  <NumberField
                    id="ttsRetentionDays"
                    label="TTS Retention Days"
                    value={draft.cache.ttsRetentionDays}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, ttsRetentionDays: value } }))}
                  />
                  <NumberField
                    id="musicRetentionDays"
                    label="Music Retention Days"
                    value={draft.cache.musicRetentionDays}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, musicRetentionDays: value } }))}
                  />
                  <SelectField
                    id="scriptReuseScope"
                    label="Script Reuse Scope"
                    value={draft.cache.scriptReuseScope}
                    options={REUSE_SCOPE_OPTIONS}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, scriptReuseScope: value } }))}
                  />
                  <SelectField
                    id="ttsReuseScope"
                    label="TTS Reuse Scope"
                    value={draft.cache.ttsReuseScope}
                    options={REUSE_SCOPE_OPTIONS}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, ttsReuseScope: value } }))}
                  />
                  <SelectField
                    id="musicReuseScope"
                    label="Music Reuse Scope"
                    value={draft.cache.musicReuseScope}
                    options={REUSE_SCOPE_OPTIONS}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, musicReuseScope: value } }))}
                  />
                  <NumberField
                    id="cleanupBatchSize"
                    label="Cleanup Batch Size"
                    value={draft.cache.cleanupBatchSize}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, cleanupBatchSize: value } }))}
                  />
                </div>
              </SettingsSection>

              <SettingsSection
                title="Programming"
                description="planning の既定値と fallback の既定を編集します。station ごとの個別 profile と合わせて使う土台設定です。"
              >
                <div className="grid gap-4 md:grid-cols-2">
                  <NumberField
                    id="defaultPlanningHorizonMinutes"
                    label="Default Planning Horizon (min)"
                    value={draft.programming.defaultPlanningHorizonMinutes}
                    min={1}
                    onChange={(value) =>
                      updateDraft((current) => ({
                        ...current,
                        programming: { ...current.programming, defaultPlanningHorizonMinutes: value },
                      }))
                    }
                  />
                  <TextField
                    id="seedImportRef"
                    label="Seed Import Ref"
                    value={draft.programming.seedImportRef}
                    onChange={(value) => updateDraft((current) => ({ ...current, programming: { ...current.programming, seedImportRef: value } }))}
                  />
                  <CheckboxField
                    id="legacyRatioFallback"
                    label="Legacy Ratio Fallback"
                    checked={draft.programming.legacyRatioFallback}
                    description="設計上の最終 fallback を許可します。"
                    onChange={(checked) => updateDraft((current) => ({ ...current, programming: { ...current.programming, legacyRatioFallback: checked } }))}
                  />
                </div>
              </SettingsSection>

              <SettingsSection title="Providers" description="default / fallback の切替と、現在登録されている endpoint / capability を編集します。provider key の追加・削除はまだ対象外です。">
                <div className="space-y-4">
                  {PROVIDER_GROUPS.map((groupKey) => (
                    <ProviderGroupEditor
                      key={groupKey}
                      groupKey={groupKey}
                      group={draft.providers[groupKey]}
                      health={connectionsMutation.data?.providers[groupKey]}
                      onDefaultChange={(providerKey) =>
                        updateDraft((current) => ({
                          ...current,
                          providers: {
                            ...current.providers,
                            [groupKey]: {
                              ...current.providers[groupKey],
                              defaultProvider: providerKey,
                              fallbackProviders: current.providers[groupKey].fallbackProviders.filter((value) => value !== providerKey),
                            },
                          },
                        }))
                      }
                      onFallbackToggle={(providerKey, checked) =>
                        updateDraft((current) => ({
                          ...current,
                          providers: {
                            ...current.providers,
                            [groupKey]: {
                              ...current.providers[groupKey],
                              fallbackProviders: checked
                                ? [...current.providers[groupKey].fallbackProviders, providerKey]
                                    .filter((value, index, array) => array.indexOf(value) === index)
                                    .filter((value) => value !== current.providers[groupKey].defaultProvider)
                                : current.providers[groupKey].fallbackProviders.filter((value) => value !== providerKey),
                            },
                          },
                        }))
                      }
                      onEndpointChange={(providerKey, field, value) =>
                        updateDraft((current) => ({
                          ...current,
                          providers: {
                            ...current.providers,
                            [groupKey]: {
                              ...current.providers[groupKey],
                              providers: {
                                ...current.providers[groupKey].providers,
                                [providerKey]: {
                                  ...current.providers[groupKey].providers[providerKey],
                                  [field]: value,
                                },
                              },
                            },
                          },
                        }))
                      }
                    />
                  ))}
                </div>
              </SettingsSection>

              <SettingsSection title="Security / Features" description="秘密値そのものではなく参照先を保持します。placeholder 配信の有効化もここで管理します。">
                <div className="grid gap-4 md:grid-cols-2">
                  <TextField
                    id="adminTokenRef"
                    label="Admin Token Ref"
                    value={draft.security.adminTokenRef ?? ""}
                    placeholder="env:SEEDSHIFT_ADMIN_TOKEN"
                    onChange={(value) =>
                      updateDraft((current) => ({
                        ...current,
                        security: { ...current.security, adminTokenRef: value.trim() ? value : null },
                      }))
                    }
                  />
                  <CheckboxField
                    id="placeholderEnabled"
                    label="Streaming Placeholder Enabled"
                    checked={draft.features.streaming.placeholderEnabled}
                    description="asset が見つからない時に placeholder を返します。"
                    onChange={(checked) =>
                      updateDraft((current) => ({
                        ...current,
                        features: {
                          ...current.features,
                          streaming: { ...current.features.streaming, placeholderEnabled: checked },
                        },
                      }))
                    }
                  />
                </div>
              </SettingsSection>
            </div>
          ) : (
            <EmptyState
              title="設定を取得できません"
              description={settingsQuery.error instanceof Error ? settingsQuery.error.message : "管理トークンの設定を確認してください。"}
            />
          )}
        </Card>

        <StationOverviewCard
          stations={stationsQuery.data ?? []}
          selectedStationId={selectedStationId}
          station={stationDetailQuery.data}
          programming={stationProgrammingQuery.data}
          onSelectStation={setSelectedStationId}
        />

        <ProgramTemplateCard
          templates={templatesQuery.data ?? []}
          selectedTemplateId={selectedTemplateId}
          template={templateDetailQuery.data}
          onSelectTemplate={setSelectedTemplateId}
        />
      </PanelColumn>

      <PanelColumn className="xl:col-span-5">
        <Card>
          <SectionHeader
            eyebrow="Health"
            title="Provider connection test"
            description="`/api/settings/test-connections` は保存済み設定に対して実行されます。未保存変更がある場合は、先に保存してから確認してください。"
          />
          <div className="space-y-3">
            <Metric label="Checked At" value={connectionsMutation.data?.checkedAt ?? "未実行"} tone={connectionsMutation.data ? "success" : "default"} />
            {connectionsMutation.data ? (
              <ConnectionResults response={connectionsMutation.data} />
            ) : (
              <EmptyState
                title="接続テストはまだです"
                description={connectionsMutation.error instanceof Error ? connectionsMutation.error.message : "上のボタンで保存済み設定の疎通確認を実行できます。"}
              />
            )}
          </div>
        </Card>

        <ProgrammingPreviewCard
          stations={stationsQuery.data ?? []}
          selectedStationId={selectedStationId}
          previewDraft={previewDraft}
          previewResult={previewMutation.data}
          previewError={previewMutation.error}
          previewPending={previewMutation.isPending}
          onStationChange={setSelectedStationId}
          onPreviewDraftChange={setPreviewDraft}
          onPreview={() => previewMutation.mutate()}
        />
      </PanelColumn>
    </PanelGrid>
  );
}

function SettingsSection({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return (
    <Card className="p-4">
      <div className="mb-4 space-y-1">
        <div className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500">{title}</div>
        <p className="text-sm leading-6 text-slate-600">{description}</p>
      </div>
      {children}
    </Card>
  );
}

function InlineNotice({ tone, message }: { tone: "accent" | "warning" | "danger"; message: string }) {
  const toneClass = {
    accent: "border-teal-200 bg-teal-50 text-teal-900",
    warning: "border-amber-200 bg-amber-50 text-amber-900",
    danger: "border-rose-200 bg-rose-50 text-rose-900",
  }[tone];
  return <div className={`rounded-2xl border px-4 py-3 text-sm leading-6 ${toneClass}`}>{message}</div>;
}

function TextField({
  id,
  label,
  value,
  placeholder,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  placeholder?: string;
  onChange: (value: string) => void;
}) {
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <Input id={id} value={value} placeholder={placeholder} onChange={(event) => onChange(event.currentTarget.value)} />
    </div>
  );
}

function NumberField({
  id,
  label,
  value,
  min,
  onChange,
}: {
  id: string;
  label: string;
  value: number;
  min: number;
  onChange: (value: number) => void;
}) {
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        type="number"
        min={min}
        value={value}
        onChange={(event) => onChange(Number.isNaN(event.currentTarget.valueAsNumber) ? min : event.currentTarget.valueAsNumber)}
      />
    </div>
  );
}

function SelectField({
  id,
  label,
  value,
  options,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  options: readonly string[];
  onChange: (value: string) => void;
}) {
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <select id={id} className={SELECT_CLASS_NAME} value={value} onChange={(event) => onChange(event.currentTarget.value)}>
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    </div>
  );
}

function CheckboxField({
  id,
  label,
  checked,
  description,
  onChange,
}: {
  id: string;
  label: string;
  checked: boolean;
  description?: string;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label htmlFor={id} className="flex min-h-[84px] items-start gap-3 rounded-2xl border border-slate-200 bg-white/70 px-4 py-3">
      <input id={id} type="checkbox" checked={checked} onChange={(event) => onChange(event.currentTarget.checked)} className="mt-1 h-4 w-4 accent-teal-600" />
      <span>
        <span className="block text-sm font-semibold text-slate-950">{label}</span>
        {description ? <span className="mt-1 block text-sm leading-6 text-slate-600">{description}</span> : null}
      </span>
    </label>
  );
}

function ProviderGroupEditor({
  groupKey,
  group,
  health,
  onDefaultChange,
  onFallbackToggle,
  onEndpointChange,
}: {
  groupKey: keyof ProviderCatalog;
  group: ProviderGroup;
  health?: ProviderHealthPayload;
  onDefaultChange: (providerKey: string) => void;
  onFallbackToggle: (providerKey: string, checked: boolean) => void;
  onEndpointChange: (providerKey: string, field: "baseUrl" | "healthPath" | "timeoutMs" | "capabilities", value: string | number | string[]) => void;
}) {
  const providerKeys = Object.keys(group.providers);

  return (
    <Card tone="accent" className="p-4">
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div className="text-sm font-semibold text-slate-950">{PROVIDER_LABELS[groupKey]}</div>
        <Badge tone="accent">{group.defaultProvider}</Badge>
        {health ? <Badge tone={health.status === "UP" ? "success" : health.status === "DEGRADED" ? "warning" : "danger"}>{health.status}</Badge> : null}
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <div>
          <Label htmlFor={`${groupKey}-default`}>Default Provider</Label>
          <select
            id={`${groupKey}-default`}
            className={SELECT_CLASS_NAME}
            value={group.defaultProvider}
            onChange={(event) => onDefaultChange(event.currentTarget.value)}
          >
            {providerKeys.map((providerKey) => (
              <option key={providerKey} value={providerKey}>
                {providerKey}
              </option>
            ))}
          </select>
        </div>

        <div>
          <Label>Fallback Providers</Label>
          <div className="space-y-2">
            {providerKeys.filter((providerKey) => providerKey !== group.defaultProvider).length > 0 ? (
              providerKeys
                .filter((providerKey) => providerKey !== group.defaultProvider)
                .map((providerKey) => (
                  <label key={providerKey} className="flex items-center gap-2 rounded-2xl border border-slate-200 bg-white/70 px-4 py-3 text-sm text-slate-700">
                    <input
                      type="checkbox"
                      checked={group.fallbackProviders.includes(providerKey)}
                      onChange={(event) => onFallbackToggle(providerKey, event.currentTarget.checked)}
                      className="h-4 w-4 accent-teal-600"
                    />
                    <span>{providerKey}</span>
                  </label>
                ))
            ) : (
              <div className="rounded-2xl border border-dashed border-slate-300 bg-white/60 px-4 py-3 text-sm text-slate-500">
                追加の fallback provider はまだありません。
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="mt-4 space-y-3">
        {providerKeys.map((providerKey) => (
          <div key={providerKey} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-4">
            <div className="flex flex-wrap items-center gap-2">
              <div className="font-semibold text-slate-950">{providerKey}</div>
              {providerKey === group.defaultProvider ? <Badge tone="success">default</Badge> : null}
              {group.fallbackProviders.includes(providerKey) ? <Badge tone="warning">fallback</Badge> : null}
            </div>
            <div className="mt-3 grid gap-4 md:grid-cols-2">
              <TextField
                id={`${groupKey}-${providerKey}-baseUrl`}
                label="Base URL"
                value={group.providers[providerKey].baseUrl}
                onChange={(value) => onEndpointChange(providerKey, "baseUrl", value)}
              />
              <TextField
                id={`${groupKey}-${providerKey}-healthPath`}
                label="Health Path"
                value={group.providers[providerKey].healthPath}
                onChange={(value) => onEndpointChange(providerKey, "healthPath", value)}
              />
              <NumberField
                id={`${groupKey}-${providerKey}-timeoutMs`}
                label="Timeout (ms)"
                value={group.providers[providerKey].timeoutMs}
                min={100}
                onChange={(value) => onEndpointChange(providerKey, "timeoutMs", value)}
              />
              <TextField
                id={`${groupKey}-${providerKey}-capabilities`}
                label="Capabilities"
                value={group.providers[providerKey].capabilities.join(", ")}
                placeholder="SCRIPT_GEN, TTS_GEN"
                onChange={(value) => onEndpointChange(providerKey, "capabilities", splitCsv(value))}
              />
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}

function ConnectionResults({ response }: { response: ConnectionsTestResponse }) {
  return (
    <div className="space-y-3">
      {Object.entries(response.providers).map(([key, health]) => (
        <ConnectionResultCard key={key} label={key} health={health} />
      ))}
    </div>
  );
}

function ConnectionResultCard({ label, health }: { label: string; health: ProviderHealthPayload }) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="font-semibold text-slate-950">{label}</div>
        <Badge tone={health.status === "UP" ? "success" : health.status === "DEGRADED" ? "warning" : "danger"}>{health.status}</Badge>
      </div>
      <div className="mt-2 text-sm leading-6 text-slate-600">{health.message}</div>
      <div className="mt-3 grid gap-3 text-sm text-slate-500 md:grid-cols-2">
        <div>
          <div className="text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-400">Provider</div>
          <div className="mt-1">{health.providerKey ?? "-"}</div>
        </div>
        <div>
          <div className="text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-400">Response Time</div>
          <div className="mt-1">{health.responseTimeMs != null ? `${health.responseTimeMs} ms` : "-"}</div>
        </div>
      </div>
    </div>
  );
}

function createDraft(settings: SettingsResponse): SettingsUpdateRequest {
  return {
    version: settings.version,
    schemaVersion: settings.schemaVersion,
    server: {
      bindHost: settings.server.bindHost,
      port: settings.server.port,
    },
    paths: {
      dataRoot: settings.paths.dataRoot,
      musicLibrary: settings.paths.musicLibrary,
    },
    playout: {
      targetReadyCount: settings.playout.targetReadyCount,
      minimumReadyCount: settings.playout.minimumReadyCount,
      minReadyDurationMs: settings.playout.minReadyDurationMs,
      maxPreparedDurationMs: settings.playout.maxPreparedDurationMs,
      maxPreparedBlocks: settings.playout.maxPreparedBlocks,
      scriptAheadCount: settings.playout.scriptAheadCount,
      ttsAheadCount: settings.playout.ttsAheadCount,
      musicAheadCount: settings.playout.musicAheadCount,
      idlePrefetchEnabled: settings.playout.idlePrefetchEnabled,
    },
    cache: {
      scriptMaxBytes: settings.cache.scriptMaxBytes,
      ttsMaxBytes: settings.cache.ttsMaxBytes,
      musicMaxBytes: settings.cache.musicMaxBytes,
      scriptRetentionDays: settings.cache.scriptRetentionDays,
      ttsRetentionDays: settings.cache.ttsRetentionDays,
      musicRetentionDays: settings.cache.musicRetentionDays,
      scriptReuseScope: settings.cache.scriptReuseScope,
      ttsReuseScope: settings.cache.ttsReuseScope,
      musicReuseScope: settings.cache.musicReuseScope,
      cleanupBatchSize: settings.cache.cleanupBatchSize,
    },
    programming: {
      defaultPlanningHorizonMinutes: settings.programming.defaultPlanningHorizonMinutes,
      legacyRatioFallback: settings.programming.legacyRatioFallback,
      seedImportRef: settings.programming.seedImportRef,
    },
    providers: cloneProviders(settings.providers),
    security: {
      adminTokenRef: settings.security.adminTokenRef,
    },
    features: cloneFeatures(settings.features),
  };
}

function cloneProviders(providers: ProviderCatalog): ProviderCatalog {
  return {
    llm: cloneProviderGroup(providers.llm),
    tts: cloneProviderGroup(providers.tts),
    musicGen: cloneProviderGroup(providers.musicGen),
  };
}

function cloneProviderGroup(group: ProviderGroup): ProviderGroup {
  return {
    defaultProvider: group.defaultProvider,
    fallbackProviders: [...group.fallbackProviders],
    providers: Object.fromEntries(
      Object.entries(group.providers).map(([providerKey, endpoint]) => [
        providerKey,
        {
          baseUrl: endpoint.baseUrl,
          healthPath: endpoint.healthPath,
          timeoutMs: endpoint.timeoutMs,
          capabilities: [...endpoint.capabilities],
        },
      ]),
    ),
  };
}

function cloneFeatures(features: FeatureSettings): FeatureSettings {
  return {
    streaming: {
      placeholderEnabled: features.streaming.placeholderEnabled,
    },
  };
}

function splitCsv(value: string) {
  return value
    .split(",")
    .map((entry) => entry.trim())
    .filter((entry, index, array) => entry.length > 0 && array.indexOf(entry) === index);
}

function formatTimestamp(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat("ja-JP", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}

function isUnsafeBindHost(bindHost: string) {
  const normalized = bindHost.trim().toLowerCase();
  return normalized !== "127.0.0.1" && normalized !== "localhost" && normalized !== "::1";
}

type PreviewDraft = {
  at: string;
  pendingLetterCount: number;
  providerStates: ProgrammingPreviewRequest["providerStates"];
};

function StationOverviewCard({
  stations,
  selectedStationId,
  station,
  programming,
  onSelectStation,
}: {
  stations: StationSummary[];
  selectedStationId: string | null;
  station?: StationDetail;
  programming?: StationProgrammingResponse;
  onSelectStation: (stationId: string | null) => void;
}) {
  return (
    <Card className="mt-4">
      <SectionHeader
        eyebrow="Stations"
        title="Station overview"
        description="局情報と station ごとの編成 profile を settings 画面から参照します。"
      />
      {!stations.length ? (
        <EmptyState title="局がまだありません" description="`/api/stations` に局が追加されるとここに表示されます。" />
      ) : (
        <div className="space-y-4">
          <div>
            <Label htmlFor="settings-station-select">Station</Label>
            <select
              id="settings-station-select"
              className={SELECT_CLASS_NAME}
              value={selectedStationId ?? ""}
              onChange={(event) => onSelectStation(event.currentTarget.value || null)}
            >
              {stations.map((entry) => (
                <option key={entry.id} value={entry.id}>
                  {entry.name} ({entry.frequencyMHz} MHz)
                </option>
              ))}
            </select>
          </div>
          {station ? (
            <div className="space-y-4">
              <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                <Metric label="Genre" value={station.genre} />
                <Metric label="Frequency" value={`${station.frequencyMHz} MHz`} />
                <Metric label="Persona" value={station.languagePersonaId} />
                <Metric label="Voice" value={station.defaultVoiceProfileId} />
              </div>
              <KeyValueGrid
                title="Programming policy"
                entries={[
                  ["Enabled", programming?.enabled ? "true" : "false"],
                  ["Default Template", programming?.defaultTemplateId ?? station.programming.defaultTemplateId ?? "-"],
                  ["Fallback", programming?.fallbackStrategy ?? station.programming.fallbackStrategy],
                  ["Planning Horizon", `${programming?.planningHorizonMinutes ?? station.programming.planningHorizonMinutes} min`],
                ]}
              />
              <KeyValueGrid
                title="Pre-generation"
                entries={[
                  ["Mode", programming?.preGeneration.mode ?? station.programming.preGeneration.mode],
                  ["Max Prepared Minutes", programming?.preGeneration.maxPreparedMinutes ?? station.programming.preGeneration.maxPreparedMinutes],
                  ["Max Prepared Blocks", programming?.preGeneration.maxPreparedBlocks ?? station.programming.preGeneration.maxPreparedBlocks],
                  ["Prefer Cache Reuse", String(programming?.preGeneration.preferCacheReuse ?? station.programming.preGeneration.preferCacheReuse)],
                ]}
              />
              <KeyValueGrid
                title="Replay"
                entries={[
                  ["Intensity", programming?.replay.intensity ?? station.programming.replay.intensity],
                  [
                    "Eligible Segments",
                    formatSegmentTypes(programming?.replay.eligibleSegmentTypes ?? station.programming.replay.eligibleSegmentTypes),
                  ],
                  ["Cooldown", `${programming?.replay.cooldownHours ?? station.programming.replay.cooldownHours} h`],
                  ["Max Share", `${programming?.replay.maxReplaySharePercent ?? station.programming.replay.maxReplaySharePercent}%`],
                ]}
              />
              <KeyValueGrid
                title="Composition"
                entries={[
                  [
                    "Target Shares",
                    formatShareMap(programming?.composition.targetSegmentShares ?? station.programming.composition.targetSegmentShares),
                  ],
                  [
                    "Max Consecutive Talk",
                    programming?.composition.maxConsecutiveTalkSegments ?? station.programming.composition.maxConsecutiveTalkSegments,
                  ],
                  [
                    "Music Break Interval",
                    `${programming?.composition.musicBreakIntervalMinutes ?? station.programming.composition.musicBreakIntervalMinutes} min`,
                  ],
                  [
                    "Letter Boost Threshold",
                    programming?.composition.letterPriorityBoostThreshold ?? station.programming.composition.letterPriorityBoostThreshold,
                  ],
                ]}
              />
              <div className="space-y-2">
                <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Rules</div>
                {programming?.rules.length ? (
                  programming.rules.map((rule) => (
                    <div key={rule.id} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone="accent">Priority {rule.priority}</Badge>
                        <Badge tone="default">{rule.templateId}</Badge>
                      </div>
                      <div className="mt-2 text-sm text-slate-600">
                        {rule.days.join(", ")} / {rule.startTime}-{rule.endTime} / minimum letters {rule.minimumPendingLetters}
                      </div>
                      <div className="mt-1 text-xs text-slate-500">
                        required states: {rule.requiredProviderStates.length ? rule.requiredProviderStates.join(", ") : "none"}
                      </div>
                    </div>
                  ))
                ) : (
                  <EmptyState title="Programming rule はまだありません" description="自動番組生成ルールが未設定の場合は fallback で運用されます。" />
                )}
              </div>
            </div>
          ) : (
            <EmptyState title="局詳細を取得できません" description="station を選択すると概要と programming policy を表示します。" />
          )}
        </div>
      )}
    </Card>
  );
}

function ProgramTemplateCard({
  templates,
  selectedTemplateId,
  template,
  onSelectTemplate,
}: {
  templates: ProgramTemplateSummary[];
  selectedTemplateId: string | null;
  template?: ProgramTemplateDetail;
  onSelectTemplate: (templateId: string | null) => void;
}) {
  return (
    <Card className="mt-4">
      <SectionHeader
        eyebrow="Templates"
        title="Program templates"
        description="番組テンプレートの一覧と slot 構成を読み取り専用で確認します。"
      />
      {!templates.length ? (
        <EmptyState title="Program Template はまだありません" description="`/api/program-templates` の一覧がここに表示されます。" />
      ) : (
        <div className="space-y-4">
          <div>
            <Label htmlFor="program-template-select">Template</Label>
            <select
              id="program-template-select"
              className={SELECT_CLASS_NAME}
              value={selectedTemplateId ?? ""}
              onChange={(event) => onSelectTemplate(event.currentTarget.value || null)}
            >
              {templates.map((entry) => (
                <option key={entry.id} value={entry.id}>
                  {entry.name} ({entry.scope})
                </option>
              ))}
            </select>
          </div>
          <div className="grid gap-3">
            {templates.map((entry) => (
              <button
                key={entry.id}
                type="button"
                onClick={() => onSelectTemplate(entry.id)}
                className={`rounded-2xl border px-4 py-3 text-left transition ${
                  entry.id === selectedTemplateId ? "border-slate-950 bg-slate-950 text-white" : "border-slate-200 bg-white/80 text-slate-800"
                }`}
              >
                <div className="flex flex-wrap items-center gap-2">
                  <div className="font-semibold">{entry.name}</div>
                  <Badge tone={entry.isActive ? "success" : "warning"}>{entry.isActive ? "ACTIVE" : "INACTIVE"}</Badge>
                  <Badge tone="default">{entry.scope}</Badge>
                </div>
                <div className="mt-1 text-sm opacity-80">
                  {entry.stationId ?? "GLOBAL"} / {entry.targetDurationMinutes} min / horizon {entry.planningHorizonMinutes} min
                </div>
              </button>
            ))}
          </div>
          {template ? (
            <div className="space-y-3 rounded-3xl border border-slate-200 bg-slate-50 px-4 py-4">
              <div className="flex flex-wrap items-center gap-2">
                <div className="text-lg font-semibold text-slate-950">{template.name}</div>
                <Badge tone="accent">v{template.version}</Badge>
                {template.fallbackTemplateId ? <Badge tone="default">fallback: {template.fallbackTemplateId}</Badge> : null}
              </div>
              <KeyValueGrid
                title="Template facts"
                entries={[
                  ["Scope", template.scope],
                  ["Station", template.stationId ?? "GLOBAL"],
                  ["Target Duration", `${template.targetDurationMinutes} min`],
                  ["Planning Horizon", `${template.planningHorizonMinutes} min`],
                ]}
              />
              <div className="space-y-2">
                <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Slots</div>
                {template.slots.map((slot) => (
                  <div key={slot.slotId} className="rounded-2xl bg-white px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <div className="font-semibold text-slate-950">{slot.slotId}</div>
                      <Badge tone="default">{slot.role}</Badge>
                      <Badge tone={slot.constraintMode === "HARD" ? "danger" : "accent"}>{slot.constraintMode}</Badge>
                    </div>
                    <div className="mt-2 text-sm text-slate-600">
                      candidate: {formatSegmentTypes(slot.candidateSegmentTypes)} / fallback: {formatSegmentTypes(slot.fallbackSegmentTypes)}
                    </div>
                    <div className="mt-1 text-xs text-slate-500">
                      target: {formatDurationMs(slot.targetDurationMs)} / policy: {formatJson(slot.slotPolicy)}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : (
            <EmptyState title="テンプレート詳細を取得できません" description="一覧からテンプレートを選ぶと slot 構成を確認できます。" />
          )}
        </div>
      )}
    </Card>
  );
}

function ProgrammingPreviewCard({
  stations,
  selectedStationId,
  previewDraft,
  previewResult,
  previewError,
  previewPending,
  onStationChange,
  onPreviewDraftChange,
  onPreview,
}: {
  stations: StationSummary[];
  selectedStationId: string | null;
  previewDraft: PreviewDraft;
  previewResult?: ProgrammingPreviewResponse;
  previewError: unknown;
  previewPending: boolean;
  onStationChange: (stationId: string | null) => void;
  onPreviewDraftChange: Dispatch<SetStateAction<PreviewDraft>>;
  onPreview: () => void;
}) {
  return (
    <Card className="mt-4">
      <SectionHeader
        eyebrow="Preview"
        title="Programming preview"
        description="station, pending letters, provider state を指定して preview API の結果を確認します。"
      />
      {!stations.length ? (
        <EmptyState title="Preview 対象の station がありません" description="局を追加すると preview を試せます。" />
      ) : (
        <div className="space-y-4">
          <div>
            <Label htmlFor="preview-station-select">Station</Label>
            <select
              id="preview-station-select"
              className={SELECT_CLASS_NAME}
              value={selectedStationId ?? ""}
              onChange={(event) => onStationChange(event.currentTarget.value || null)}
            >
              {stations.map((entry) => (
                <option key={entry.id} value={entry.id}>
                  {entry.name}
                </option>
              ))}
            </select>
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <div>
              <Label htmlFor="preview-at">At</Label>
              <Input
                id="preview-at"
                type="datetime-local"
                value={previewDraft.at}
                onChange={(event) =>
                  onPreviewDraftChange((current) => ({
                    ...current,
                    at: event.currentTarget.value,
                  }))
                }
              />
            </div>
            <NumberField
              id="preview-pending-letters"
              label="Pending Letters"
              value={previewDraft.pendingLetterCount}
              min={0}
              onChange={(value) =>
                onPreviewDraftChange((current) => ({
                  ...current,
                  pendingLetterCount: value,
                }))
              }
            />
            {(["musicGen", "tts", "llm"] as const).map((providerKey) => (
              <div key={providerKey}>
                <Label htmlFor={`preview-${providerKey}`}>{providerKey}</Label>
                <select
                  id={`preview-${providerKey}`}
                  className={SELECT_CLASS_NAME}
                  value={previewDraft.providerStates[providerKey]}
                  onChange={(event) =>
                    onPreviewDraftChange((current) => ({
                      ...current,
                      providerStates: {
                        ...current.providerStates,
                        [providerKey]: event.currentTarget.value,
                      },
                    }))
                  }
                >
                  {PROGRAMMING_STATE_OPTIONS.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              </div>
            ))}
          </div>
          <Button tone="primary" disabled={!selectedStationId || previewPending} onClick={onPreview}>
            {previewPending ? "Previewing..." : "Run Preview"}
          </Button>
          {previewResult ? (
            <div className="space-y-3 rounded-3xl border border-slate-200 bg-slate-50 px-4 py-4">
              <div className="flex flex-wrap items-center gap-2">
                <div className="text-lg font-semibold text-slate-950">{previewResult.program.title}</div>
                {previewResult.selectedTemplateId ? <Badge tone="default">{previewResult.selectedTemplateId}</Badge> : null}
                {previewResult.fallbackApplied ? <Badge tone="warning">fallback applied</Badge> : <Badge tone="success">template resolved</Badge>}
              </div>
              <div className="text-sm text-slate-600">planned duration: {formatDurationMs(previewResult.program.plannedDurationMs)}</div>
              <div className="space-y-2">
                {previewResult.slots.map((slot) => (
                  <div key={slot.slotId} className="rounded-2xl bg-white px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <div className="font-semibold text-slate-950">{slot.slotId}</div>
                      <Badge tone="default">{slot.role}</Badge>
                      <Badge tone={slot.constraintMode === "HARD" ? "danger" : "accent"}>{slot.constraintMode}</Badge>
                    </div>
                    <div className="mt-1 text-sm text-slate-600">target duration: {formatDurationMs(slot.targetDurationMs)}</div>
                  </div>
                ))}
              </div>
              {previewResult.validationWarnings.length ? (
                <div className="space-y-2">
                  <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Validation warnings</div>
                  {previewResult.validationWarnings.map((warning) => (
                    <InlineNotice key={warning.code} tone="warning" message={`${warning.code}: ${warning.message}`} />
                  ))}
                </div>
              ) : null}
            </div>
          ) : previewError instanceof Error ? (
            <EmptyState title="Preview を取得できません" description={previewError.message} />
          ) : (
            <EmptyState title="Preview はまだ未実行です" description="station と provider state を指定すると結果をここに表示します。" />
          )}
        </div>
      )}
    </Card>
  );
}

function KeyValueGrid({ title, entries }: { title: string; entries: Array<[string, ReactNode]> }) {
  return (
    <div className="rounded-3xl border border-slate-200 bg-slate-50 px-4 py-4">
      <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">{title}</div>
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        {entries.map(([label, value]) => (
          <div key={label}>
            <div className="text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-400">{label}</div>
            <div className="mt-1 text-sm leading-6 text-slate-700">{value}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

function createPreviewDraft(): PreviewDraft {
  return {
    at: toLocalDateTimeValue(new Date()),
    pendingLetterCount: 0,
    providerStates: {
      musicGen: "UNKNOWN",
      tts: "UNKNOWN",
      llm: "UNKNOWN",
    },
  };
}

function toPreviewIso(value: string) {
  const date = value ? new Date(value) : new Date();
  if (Number.isNaN(date.getTime())) {
    return new Date().toISOString();
  }
  return date.toISOString();
}

function toLocalDateTimeValue(date: Date) {
  const offsetMs = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offsetMs).toISOString().slice(0, 16);
}

function formatDurationMs(value: number) {
  const totalSeconds = Math.max(0, Math.round(value / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}m ${seconds}s`;
}

function formatSegmentTypes(values: readonly string[]) {
  return values.length ? values.join(", ") : "-";
}

function formatShareMap(value: Record<string, number>) {
  return Object.entries(value)
    .map(([key, share]) => `${key}:${share}`)
    .join(" / ");
}

function formatJson(value: Record<string, unknown>) {
  const entries = Object.entries(value);
  if (!entries.length) {
    return "{}";
  }
  return entries
    .map(([key, entryValue]) => `${key}=${String(entryValue)}`)
    .join(", ");
}
