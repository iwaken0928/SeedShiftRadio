"use client";

import Link from "next/link";
import { useDeferredValue, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getHealth, getMonitorSummary, getRadioProgram } from "@/lib/api";
import { extractWorkerStatusDetails, getProviderMetadataHighlights, type WorkerStatusDetails } from "@/lib/monitor-provider-health";
import { formatSafeDisplayText } from "@/lib/safe-metadata";
import { PanelColumn, PanelGrid } from "@/components/markdown";
import { Badge, Card, EmptyState, Input, Metric, SectionHeader } from "@/components/ui";
import type { CacheTypeMetrics, MonitorAuditEvent, MonitorProviderJob, ProviderHealthPayload } from "@/lib/types";

const LIVE_REFRESH_INTERVAL_MS = 10_000;
const PROVIDER_STATUS_FILTERS = ["ALL", "UP", "DEGRADED", "DOWN"] as const;

type ProviderStatusFilter = (typeof PROVIDER_STATUS_FILTERS)[number];

export function MonitorDashboard() {
  const hasAdminToken = true;
  const [providerStatusFilter, setProviderStatusFilter] = useState<ProviderStatusFilter>("ALL");
  const [jobSearchText, setJobSearchText] = useState("");
  const [auditSearchText, setAuditSearchText] = useState("");
  const deferredJobSearchText = useDeferredValue(jobSearchText.trim().toLowerCase());
  const deferredAuditSearchText = useDeferredValue(auditSearchText.trim().toLowerCase());

  const summaryQuery = useQuery({
    queryKey: ["monitor", "summary"],
    queryFn: getMonitorSummary,
    enabled: hasAdminToken,
    retry: false,
    refetchInterval: LIVE_REFRESH_INTERVAL_MS,
    refetchOnWindowFocus: false,
  });

  const healthQuery = useQuery({
    queryKey: ["health"],
    queryFn: getHealth,
    enabled: hasAdminToken,
    retry: false,
    refetchInterval: LIVE_REFRESH_INTERVAL_MS,
    refetchOnWindowFocus: false,
  });

  const programQuery = useQuery({
    queryKey: ["radio", "program"],
    queryFn: getRadioProgram,
    enabled: hasAdminToken && Boolean(summaryQuery.data?.sessionId) && summaryQuery.data?.state !== "PREPARING",
    retry: false,
    refetchInterval: LIVE_REFRESH_INTERVAL_MS,
    refetchOnWindowFocus: false,
  });

  if (!hasAdminToken) {
    return (
      <PanelGrid>
        <PanelColumn className="xl:col-span-7">
          <Card>
            <SectionHeader
              eyebrow="監視"
              title="管理者ログインが必要です"
              description="`/monitor` は管理トークン前提の監視画面です。公開 UI には表示せず、直接アクセスされた場合だけ案内を出します。"
            />
            <EmptyState
              title="監視画面は管理トークンが必要です"
              description="管理者としてログインしてから開いてください。"
            />
          </Card>
        </PanelColumn>
      </PanelGrid>
    );
  }

  const summary = summaryQuery.data;
  const providerEntries = Object.entries(summary?.providerHealth ?? {}).filter((entry) =>
    matchesProviderStatusFilter(entry[1], providerStatusFilter),
  );
  const workerStatusEntries = providerEntries
    .map(([key, health]) => ({
      key,
      health,
      details: extractWorkerStatusDetails(health),
    }))
    .filter((entry): entry is { key: string; health: ProviderHealthPayload; details: WorkerStatusDetails } => entry.details != null);
  const runningJobs = (summary?.runningJobs ?? []).filter((job) => matchesJobSearch(job, deferredJobSearchText));
  const recentJobs = (summary?.recentJobs ?? []).filter((job) => matchesJobSearch(job, deferredJobSearchText));
  const auditEvents = (summary?.auditEvents ?? []).filter((event) => matchesAuditSearch(event, deferredAuditSearchText));

  return (
    <PanelGrid>
      <PanelColumn className="xl:col-span-7">
        <Card>
          <SectionHeader
            eyebrow="監視"
            title="稼働状況サマリー"
            description="10秒ごとに再取得し、Provider、再生キュー、番組、生成ジョブの状態を追跡します。"
          />
          <div className="mb-5 flex flex-col gap-3 rounded-2xl border border-teal-200 bg-teal-50/80 px-4 py-3 text-sm text-slate-700 sm:flex-row sm:items-center sm:justify-between">
            <p>ここに表示する再生状態は Server の正本です。実際のブラウザー音声はラジオ画面で開始・操作します。</p>
            <Link href="/" className="interactive-control shrink-0 rounded-full bg-slate-950 px-4 py-2 text-center font-semibold text-white">
              ラジオ画面を開く
            </Link>
          </div>
          {summary ? (
            <div className="space-y-6">
              <div className="grid gap-3 md:grid-cols-3 xl:grid-cols-6">
                <Metric label="再生状態" value={summary.state ?? "IDLE"} tone={summary.degraded ? "warning" : "default"} />
                <Metric label="再生可能件数" value={summary.bufferReadyCount} tone="accent" />
                <Metric label="再生可能時間" value={formatDurationMs(summary.queueReadyDurationMs)} tone="accent" />
                <Metric label="未処理レター" value={summary.pendingLetterCount} />
                <Metric label="局 ID" value={summary.stationId ?? "なし"} />
                <Metric label="更新日時" value={formatInstant(summary.updatedAt)} />
              </div>

              <section className="space-y-3">
                <SectionHeader eyebrow="再放送" title="アーカイブ候補と再放送実績" description="再放送候補と直近の再生に占めるアーカイブ再放送の割合です。" />
                <div className="grid gap-3 md:grid-cols-4">
                  <Metric label="利用可能候補" value={`${summary.archive.eligibleArchiveCount} / ${summary.archive.totalArchiveCount}`} tone="accent" />
                  <Metric label="再放送率" value={formatPercent(summary.archive.archiveReplayRate)} />
                  <Metric label="再放送回数" value={summary.archive.archiveReplayCount} />
                  <Metric label="総再生回数" value={summary.archive.totalPlaybackCount} />
                </div>
              </section>

              <section className="space-y-3">
                <SectionHeader eyebrow="番組" title="現在の番組" description="現在の番組ブロックとテンプレートの版を表示します。" />
                {programQuery.data ? (
                  <div className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-4">
                    <div className="flex flex-wrap items-center gap-2">
                      <div className="font-semibold text-slate-950">{programQuery.data.title}</div>
                      <Badge tone="accent">{programQuery.data.status}</Badge>
                      <Badge tone="default">{programQuery.data.templateId ?? "no-template"}</Badge>
                      <Badge tone="default">v{programQuery.data.templateVersion ?? "-"}</Badge>
                    </div>
                    <div className="mt-3 grid gap-3 text-sm text-slate-600 md:grid-cols-3">
                      <div>残りの番組枠: {programQuery.data.remainingSlotCount}</div>
                      <div>予定時間: {formatDurationMs(programQuery.data.plannedDurationMs)}</div>
                      <div>開始日時: {formatInstant(programQuery.data.startedAt)}</div>
                    </div>
                  </div>
                ) : (
                  <EmptyState
                    title="現在の番組 block はまだありません"
                    description={programQuery.error instanceof Error ? formatSafeDisplayText(programQuery.error.message) : "Tune 後に現在 block が表示されます。"}
                  />
                )}
              </section>

              <section className="space-y-3">
                <SectionHeader eyebrow="生成物" title="生成済みアセット" description="生成済み台本・音声・楽曲の保存量と再利用状況を追跡します。" />
                <div className="grid gap-3 md:grid-cols-4">
                  <Metric label="保存容量" value={formatBytes(summary.cache.byteSize)} tone="accent" />
                  <Metric label="アセット数" value={summary.cache.assetCount} />
                  <Metric label="再利用率" value={formatPercent(summary.cache.cacheHitRate)} />
                  <Metric label="期限切れ" value={summary.cache.expiredAssetCount} tone={summary.cache.expiredAssetCount > 0 ? "warning" : "default"} />
                </div>
                <div className="grid gap-3 md:grid-cols-3">
                  {Object.entries(summary.cache.byType).map(([assetType, metrics]) => (
                    <CacheMetricCard key={assetType} metrics={metrics} />
                  ))}
                </div>
              </section>

              <section className="space-y-3">
                <SectionHeader eyebrow="Provider" title="接続状態" description="状態フィルターと詳細情報から、異常箇所を切り分けます。" />
                <div className="flex flex-wrap gap-2">
                  {PROVIDER_STATUS_FILTERS.map((status) => (
                    <button
                      key={status}
                      type="button"
                      onClick={() => setProviderStatusFilter(status)}
                      className={`interactive-control rounded-full border px-3 py-1.5 text-xs font-semibold ${
                        providerStatusFilter === status ? "border-slate-950 bg-slate-950 text-white" : "border-slate-200 bg-white/80 text-slate-700"
                      }`}
                    >
                      {status}
                    </button>
                  ))}
                </div>
                {providerEntries.length ? (
                  <div className="grid gap-3">
                    {providerEntries.map(([key, value]) => (
                      <ProviderHealthCard key={key} label={key} health={value} />
                    ))}
                  </div>
                ) : (
                  <EmptyState title="条件に一致する provider はありません" description="status フィルタを戻すと表示対象を増やせます。" />
                )}

                <div className="space-y-3 pt-2">
                  <SectionHeader
                    eyebrow="Worker"
                    title="Worker の稼働状況"
                    description="MusicGen worker のキュー、モデルプロファイル、アダプター状態を確認できます。"
                  />
                  {workerStatusEntries.length ? (
                    <div className="grid gap-3 xl:grid-cols-2">
                      {workerStatusEntries.map(({ key, health, details }) => (
                        <WorkerStatusCard key={key} label={key} health={health} details={details} />
                      ))}
                    </div>
                  ) : (
                    <EmptyState
                      title="表示できる worker status はありません"
                      description="provider health metadata に worker 詳細が入っている provider だけを表示します。"
                    />
                  )}
                </div>
              </section>

              <section className="space-y-3">
                <SectionHeader eyebrow="生成ジョブ" title="実行中と直近の結果" description="キュー項目 ID、Provider、エラーコード、外部参照で検索できます。" />
                <Input value={jobSearchText} onChange={(event) => setJobSearchText(event.target.value)} placeholder="キュー項目 / Provider / エラーコードを検索" />
                <div className="grid gap-4 xl:grid-cols-2">
                  <div className="space-y-3">
                    <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">実行中のジョブ</div>
                    {runningJobs.length ? (
                      runningJobs.map((job) => <MonitorJobCard key={job.id} job={job} />)
                    ) : (
                      <EmptyState title="条件に一致する実行中ジョブはありません" />
                    )}
                  </div>
                  <div className="space-y-3">
                    <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">直近の実行結果</div>
                    {recentJobs.length ? (
                      recentJobs.map((job) => <MonitorJobCard key={job.id} job={job} />)
                    ) : (
                      <EmptyState title="条件に一致する実行結果はありません" />
                    )}
                  </div>
                </div>
              </section>
            </div>
          ) : (
            <EmptyState
              title="監視情報を取得できません"
              description={summaryQuery.error instanceof Error ? formatSafeDisplayText(summaryQuery.error.message) : "管理トークンの設定を確認してください。"}
            />
          )}
        </Card>
      </PanelColumn>

      <PanelColumn className="xl:col-span-5">
        <Card>
          <SectionHeader eyebrow="ヘルスチェック" title="Server の稼働状態" description="`/api/health` の集約値です。" />
          {healthQuery.data ? (
            <div className="grid gap-3">
              <Metric label="状態" value={healthQuery.data.status} tone={healthQuery.data.status === "UP" ? "success" : "warning"} />
              <Metric label="局数" value={healthQuery.data.stationCount} />
              <Metric label="セッション数" value={healthQuery.data.sessionCount} />
              <Metric label="キュー項目数" value={healthQuery.data.queueCount} />
              <Metric label="現在のセッション" value={healthQuery.data.currentSessionId ?? "なし"} />
            </div>
          ) : (
            <EmptyState title="health を取得できません" description={healthQuery.error instanceof Error ? formatSafeDisplayText(healthQuery.error.message) : undefined} />
          )}
        </Card>

        <Card className="mt-4">
          <SectionHeader eyebrow="監査" title="監査イベント" description="SSE 履歴由来の監査イベントを検索しながら確認します。" />
          <div className="space-y-3">
            <Input value={auditSearchText} onChange={(event) => setAuditSearchText(event.target.value)} placeholder="イベント種別 / 要約を検索" />
            {auditEvents.length ? (
              <div className="space-y-3">
                {auditEvents.map((event) => (
                  <div key={event.id} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <div className="font-semibold text-slate-950">{event.eventType}</div>
                      <Badge tone="default">{event.id}</Badge>
                    </div>
                    <div className="mt-1 text-xs uppercase tracking-[0.18em] text-slate-500">{formatInstant(event.occurredAt)}</div>
                    <div className="mt-2 text-sm text-slate-600">{formatSafeDisplayText(event.summary)}</div>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState title="条件に一致する監査イベントはありません" description="検索文字列を外すと直近イベントを確認できます。" />
            )}
          </div>
        </Card>
      </PanelColumn>
    </PanelGrid>
  );
}

function CacheMetricCard({ metrics }: { metrics: CacheTypeMetrics }) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="font-semibold text-slate-950">{metrics.assetType}</div>
        <Badge tone="default">{formatBytes(metrics.byteSize)}</Badge>
      </div>
      <div className="mt-2 grid gap-2 text-sm text-slate-600">
        <div>アセット数: {metrics.assetCount}</div>
        <div>再利用回数: {metrics.cacheHitCount}</div>
        <div>再利用率: {formatPercent(metrics.cacheHitRate)}</div>
      </div>
    </div>
  );
}

function ProviderHealthCard({ label, health }: { label: string; health: ProviderHealthPayload }) {
  const highlights = getProviderMetadataHighlights(health);

  return (
    <div className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-4">
      <div className="flex flex-wrap items-center gap-2">
        <div className="font-semibold text-slate-950">{label}</div>
        <Badge tone={statusTone(health.status)}>{health.status}</Badge>
        {health.providerKey ? <Badge tone="default">{health.providerKey}</Badge> : null}
        {highlights.map((highlight) => (
          <Badge key={`${label}-${highlight.label}`} tone="accent">
            {highlight.label}: {highlight.value}
          </Badge>
        ))}
      </div>
      <div className="mt-2 text-sm leading-6 text-slate-600">{formatSafeDisplayText(health.message)}</div>
      <div className="mt-3 grid gap-3 text-sm text-slate-500 md:grid-cols-2 xl:grid-cols-4">
        <Metric label="最終確認" value={health.lastCheckedAt ? formatInstant(health.lastCheckedAt) : "-"} />
        <Metric label="応答時間" value={health.responseTimeMs != null ? `${health.responseTimeMs} ms` : "-"} />
        <Metric label="機能" value={health.capabilities.length ? health.capabilities.join(", ") : "-"} />
        <Metric label="接続先 URL" value={formatSafeDisplayText(health.baseUrl)} />
      </div>
    </div>
  );
}

function WorkerStatusCard({
  label,
  health,
  details,
}: {
  label: string;
  health: ProviderHealthPayload;
  details: WorkerStatusDetails;
}) {
  const modelValues = details.models.length ? details.models : details.defaultModel ? [details.defaultModel] : [];
  const profileValues = details.modelProfileIds.length
    ? details.modelProfileIds
    : details.defaultModelProfileId
      ? [details.defaultModelProfileId]
      : [];

  return (
    <div className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-4">
      <div className="flex flex-wrap items-center gap-2">
        <div className="font-semibold text-slate-950">{label}</div>
        <Badge tone={statusTone(health.status)}>{health.status}</Badge>
        <Badge tone="accent">{details.adapter ?? "adapter unknown"}</Badge>
        {details.statsStatus ? <StatusBadge label="Stats" value={details.statsStatus} /> : null}
        {details.modelsStatus ? <StatusBadge label="Models" value={details.modelsStatus} /> : null}
        {details.modelsInitialized != null ? (
          <StatusBadge label="Model init" value={details.modelsInitialized ? "READY" : "NOT_READY"} />
        ) : null}
        {details.llmInitialized != null ? (
          <StatusBadge label="LM init" value={details.llmInitialized ? "READY" : "NOT_READY"} />
        ) : null}
      </div>

      <div className="mt-3 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <Metric label="キュー件数" value={formatCount(details.queueSize)} tone="accent" />
        <Metric label="待機中ジョブ" value={formatCount(details.queuedJobs)} tone={metricTone(details.queuedJobs)} />
        <Metric label="実行中ジョブ" value={formatCount(details.runningJobs)} tone={metricTone(details.runningJobs)} />
        <Metric label="平均処理秒数" value={formatSeconds(details.averageJobSeconds)} />
      </div>

      <div className="mt-4 grid gap-3 md:grid-cols-2">
        <DetailGroup
          label="既定モデル"
          description={details.loadedModel ?? details.defaultModel ?? details.selectedModel ?? "未取得"}
          footer={details.defaultModelProfileId ? `profile: ${details.defaultModelProfileId}` : "default profile 未取得"}
        />
        <DetailGroup
          label="Provider 情報"
          description={health.providerKey ?? "provider key なし"}
          footer={formatSafeDisplayText(health.baseUrl)}
        />
      </div>

      <div className="mt-4 grid gap-3 md:grid-cols-2">
        <PillList label="モデルプロファイル" values={profileValues} emptyLabel="プロファイル情報なし" />
        <PillList label="利用可能モデル" values={modelValues} emptyLabel="モデル情報なし" />
      </div>
    </div>
  );
}

function DetailGroup({ label, description, footer }: { label: string; description: string; footer: string }) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white/70 px-4 py-3">
      <div className="text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-400">{label}</div>
      <div className="mt-2 text-sm font-semibold text-slate-950">{description}</div>
      <div className="mt-1 text-xs leading-5 text-slate-500">{footer}</div>
    </div>
  );
}

function PillList({ label, values, emptyLabel }: { label: string; values: string[]; emptyLabel: string }) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white/70 px-4 py-3">
      <div className="text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-400">{label}</div>
      {values.length ? (
        <div className="mt-3 flex flex-wrap gap-2">
          {values.map((value) => (
            <Badge key={`${label}-${value}`} tone="default">
              {value}
            </Badge>
          ))}
        </div>
      ) : (
        <div className="mt-2 text-sm text-slate-500">{emptyLabel}</div>
      )}
    </div>
  );
}

function StatusBadge({ label, value }: { label: string; value: string }) {
  return (
    <Badge tone={healthIndicatorTone(value)}>
      {label}: {value}
    </Badge>
  );
}

function MonitorJobCard({ job }: { job: MonitorProviderJob }) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="font-semibold text-slate-950">{formatJobType(job.jobType)}</div>
        <Badge tone={monitorJobTone(job.status)}>{formatJobStatus(job.status)}</Badge>
        <Badge tone="default">{job.providerType}</Badge>
        {job.providerKey ? <Badge tone="accent">{job.providerKey}</Badge> : null}
      </div>
      <div className="mt-2 text-sm text-slate-600">{job.queueItemId ?? "キュー項目なし"}</div>
      <div className="mt-1 text-xs text-slate-500">
        {formatInstant(job.updatedAt)}
        {job.errorCode || job.externalRef ? " / " : ""}
        {job.errorCode ? `エラー: ${job.errorCode}` : null}
        {job.errorCode && job.externalRef ? " / " : ""}
        {job.externalRef ? `外部参照: ${formatSafeDisplayText(job.externalRef)}` : ""}
      </div>
    </div>
  );
}

function formatJobStatus(status: MonitorProviderJob["status"]) {
  return {
    QUEUED: "待機中",
    RUNNING: "実行中",
    SUCCEEDED: "成功",
    FAILED: "失敗",
    CANCELLED: "中止",
  }[status];
}

function formatJobType(jobType: MonitorProviderJob["jobType"]) {
  return {
    SCRIPT_GEN: "台本生成",
    TTS_GEN: "音声生成",
    MUSIC_GEN: "楽曲生成",
  }[jobType];
}

function monitorJobTone(status: MonitorProviderJob["status"]): "default" | "accent" | "success" | "warning" | "danger" {
  if (status === "SUCCEEDED") return "success";
  if (status === "RUNNING") return "accent";
  if (status === "FAILED") return "danger";
  if (status === "CANCELLED") return "warning";
  return "default";
}

function matchesProviderStatusFilter(health: ProviderHealthPayload, filter: ProviderStatusFilter) {
  return filter === "ALL" || health.status === filter;
}

function matchesJobSearch(job: MonitorProviderJob, searchText: string) {
  if (!searchText) {
    return true;
  }
  return [job.jobType, job.providerType, job.providerKey ?? "", job.queueItemId ?? "", job.errorCode ?? "", job.externalRef ?? ""]
    .join(" ")
    .toLowerCase()
    .includes(searchText);
}

function matchesAuditSearch(event: MonitorAuditEvent, searchText: string) {
  if (!searchText) {
    return true;
  }
  return [event.eventType, event.summary].join(" ").toLowerCase().includes(searchText);
}

function formatInstant(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat("ja-JP", {
    dateStyle: "short",
    timeStyle: "medium",
  }).format(date);
}

function formatDurationMs(value: number) {
  if (!Number.isFinite(value)) {
    return "-";
  }
  const totalSeconds = Math.max(0, Math.round(value / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}m ${seconds}s`;
}

function formatBytes(value: number) {
  if (!Number.isFinite(value) || value <= 0) {
    return "0 B";
  }
  const units = ["B", "KB", "MB", "GB"];
  let size = value;
  let unitIndex = 0;
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex += 1;
  }
  return `${size >= 10 || unitIndex === 0 ? size.toFixed(0) : size.toFixed(1)} ${units[unitIndex]}`;
}

function formatPercent(value: number) {
  if (!Number.isFinite(value)) {
    return "0%";
  }
  return `${Math.round(Math.max(0, Math.min(1, value)) * 100)}%`;
}

function formatCount(value: number | null) {
  return value != null ? String(Math.round(value)) : "-";
}

function formatSeconds(value: number | null) {
  if (value == null || !Number.isFinite(value)) {
    return "-";
  }
  return value >= 10 ? `${value.toFixed(0)} s` : `${value.toFixed(1)} s`;
}

function statusTone(status: ProviderHealthPayload["status"]) {
  if (status === "UP") {
    return "success";
  }
  if (status === "DEGRADED") {
    return "warning";
  }
  return "danger";
}

function healthIndicatorTone(value: string): "default" | "success" | "warning" | "danger" | "accent" {
  const normalized = value.trim().toUpperCase();
  if (normalized === "UP" || normalized === "OK" || normalized === "READY" || normalized === "SUCCESS") {
    return "success";
  }
  if (normalized === "DOWN" || normalized === "ERROR" || normalized === "FAILED") {
    return "danger";
  }
  if (normalized === "DEGRADED" || normalized === "WARN" || normalized === "WARNING") {
    return "warning";
  }
  return "default";
}

function metricTone(value: number | null): "default" | "accent" | "warning" | "success" {
  if (value == null) {
    return "default";
  }
  if (value === 0) {
    return "success";
  }
  if (value >= 5) {
    return "warning";
  }
  return "accent";
}
