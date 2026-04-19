"use client";

import { useDeferredValue, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getHealth, getMonitorSummary, getRadioProgram } from "@/lib/api";
import { getAdminToken } from "@/lib/env";
import { PanelColumn, PanelGrid } from "@/components/markdown";
import { Badge, Card, EmptyState, Input, Metric, SectionHeader } from "@/components/ui";
import type { CacheTypeMetrics, MonitorAuditEvent, MonitorProviderJob, ProviderHealthPayload } from "@/lib/types";

const LIVE_REFRESH_INTERVAL_MS = 10_000;
const PROVIDER_STATUS_FILTERS = ["ALL", "UP", "DEGRADED", "DOWN"] as const;

type ProviderStatusFilter = (typeof PROVIDER_STATUS_FILTERS)[number];

export function MonitorDashboard() {
  const hasAdminToken = Boolean(getAdminToken());
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
    enabled: hasAdminToken,
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
              eyebrow="Monitor"
              title="Admin token required"
              description="`/monitor` は管理トークン前提の監視画面です。公開 UI には表示せず、直接アクセスされた場合だけ案内を出します。"
            />
            <EmptyState
              title="監視画面は管理トークンが必要です"
              description="`NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN` または `NEXT_PUBLIC_ADMIN_TOKEN` を設定してから開いてください。"
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
  const runningJobs = (summary?.runningJobs ?? []).filter((job) => matchesJobSearch(job, deferredJobSearchText));
  const recentErrors = (summary?.recentErrors ?? []).filter((job) => matchesJobSearch(job, deferredJobSearchText));
  const auditEvents = (summary?.auditEvents ?? []).filter((event) => matchesAuditSearch(event, deferredAuditSearchText));

  return (
    <PanelGrid>
      <PanelColumn className="xl:col-span-7">
        <Card>
          <SectionHeader
            eyebrow="Monitor"
            title="Operational summary"
            description="監視サマリは 10 秒ごとに再取得し、provider health と queue/program 状態を追跡します。"
          />
          {summary ? (
            <div className="space-y-6">
              <div className="grid gap-3 md:grid-cols-5">
                <Metric label="State" value={summary.state ?? "IDLE"} tone={summary.degraded ? "warning" : "default"} />
                <Metric label="Buffer Ready" value={summary.bufferReadyCount} tone="accent" />
                <Metric label="Pending Letters" value={summary.pendingLetterCount} />
                <Metric label="Station" value={summary.stationId ?? "none"} />
                <Metric label="Updated" value={formatInstant(summary.updatedAt)} />
              </div>

              <section className="space-y-3">
                <SectionHeader eyebrow="Program" title="Current program block" description="監視画面でも現在の block / template version を見える化します。" />
                {programQuery.data ? (
                  <div className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-4">
                    <div className="flex flex-wrap items-center gap-2">
                      <div className="font-semibold text-slate-950">{programQuery.data.title}</div>
                      <Badge tone="accent">{programQuery.data.status}</Badge>
                      <Badge tone="default">{programQuery.data.templateId ?? "no-template"}</Badge>
                      <Badge tone="default">v{programQuery.data.templateVersion ?? "-"}</Badge>
                    </div>
                    <div className="mt-3 grid gap-3 text-sm text-slate-600 md:grid-cols-3">
                      <div>Remaining slots: {programQuery.data.remainingSlotCount}</div>
                      <div>Planned duration: {formatDurationMs(programQuery.data.plannedDurationMs)}</div>
                      <div>Started at: {formatInstant(programQuery.data.startedAt)}</div>
                    </div>
                  </div>
                ) : (
                  <EmptyState
                    title="現在の番組 block はまだありません"
                    description={programQuery.error instanceof Error ? programQuery.error.message : "Tune 後に現在 block が表示されます。"}
                  />
                )}
              </section>

              <section className="space-y-3">
                <SectionHeader eyebrow="Cache" title="Generated assets" description="生成済み script / TTS / music の保存量と再利用状況を追跡します。" />
                <div className="grid gap-3 md:grid-cols-4">
                  <Metric label="Bytes" value={formatBytes(summary.cache.byteSize)} tone="accent" />
                  <Metric label="Assets" value={summary.cache.assetCount} />
                  <Metric label="Cache Hit Rate" value={formatPercent(summary.cache.cacheHitRate)} />
                  <Metric label="Expired" value={summary.cache.expiredAssetCount} tone={summary.cache.expiredAssetCount > 0 ? "warning" : "default"} />
                </div>
                <div className="grid gap-3 md:grid-cols-3">
                  {Object.entries(summary.cache.byType).map(([assetType, metrics]) => (
                    <CacheMetricCard key={assetType} metrics={metrics} />
                  ))}
                </div>
              </section>

              <section className="space-y-3">
                <SectionHeader eyebrow="Providers" title="Provider health" description="状態フィルタと詳細情報で provider の異常切り分けをしやすくします。" />
                <div className="flex flex-wrap gap-2">
                  {PROVIDER_STATUS_FILTERS.map((status) => (
                    <button
                      key={status}
                      type="button"
                      onClick={() => setProviderStatusFilter(status)}
                      className={`rounded-full border px-3 py-1.5 text-xs font-semibold ${
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
                      <div key={key} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-4">
                        <div className="flex flex-wrap items-center gap-2">
                          <div className="font-semibold text-slate-950">{key}</div>
                          <Badge tone={value.status === "UP" ? "success" : value.status === "DEGRADED" ? "warning" : "danger"}>
                            {value.status}
                          </Badge>
                          {value.providerKey ? <Badge tone="default">{value.providerKey}</Badge> : null}
                        </div>
                        <div className="mt-2 text-sm leading-6 text-slate-600">{value.message}</div>
                        <div className="mt-3 grid gap-3 text-sm text-slate-500 md:grid-cols-2 xl:grid-cols-4">
                          <Metric label="Last Checked" value={value.lastCheckedAt ? formatInstant(value.lastCheckedAt) : "-"} />
                          <Metric label="Response" value={value.responseTimeMs != null ? `${value.responseTimeMs} ms` : "-"} />
                          <Metric label="Capabilities" value={value.capabilities.length ? value.capabilities.join(", ") : "-"} />
                          <Metric label="Base URL" value={value.baseUrl ?? "-"} />
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <EmptyState title="条件に一致する provider はありません" description="status フィルタを戻すと表示対象を増やせます。" />
                )}
              </section>

              <section className="space-y-3">
                <SectionHeader eyebrow="Jobs" title="Running jobs / recent errors" description="queueItemId, providerKey, errorCode, externalRef を検索できます。" />
                <Input value={jobSearchText} onChange={(event) => setJobSearchText(event.target.value)} placeholder="queue item / provider / error code を検索" />
                <div className="grid gap-4 xl:grid-cols-2">
                  <div className="space-y-3">
                    <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Running jobs</div>
                    {runningJobs.length ? (
                      runningJobs.map((job) => <MonitorJobCard key={job.id} job={job} />)
                    ) : (
                      <EmptyState title="条件に一致する running job はありません" />
                    )}
                  </div>
                  <div className="space-y-3">
                    <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Recent errors</div>
                    {recentErrors.length ? (
                      recentErrors.map((job) => <MonitorJobCard key={job.id} job={job} />)
                    ) : (
                      <EmptyState title="条件に一致する recent error はありません" />
                    )}
                  </div>
                </div>
              </section>
            </div>
          ) : (
            <EmptyState
              title="監視情報を取得できません"
              description={summaryQuery.error instanceof Error ? summaryQuery.error.message : "管理トークンの設定を確認してください。"}
            />
          )}
        </Card>
      </PanelColumn>

      <PanelColumn className="xl:col-span-5">
        <Card>
          <SectionHeader eyebrow="Health" title="Server health" description="`/api/health` の集約値です。" />
          {healthQuery.data ? (
            <div className="grid gap-3">
              <Metric label="Status" value={healthQuery.data.status} tone={healthQuery.data.status === "UP" ? "success" : "warning"} />
              <Metric label="Stations" value={healthQuery.data.stationCount} />
              <Metric label="Sessions" value={healthQuery.data.sessionCount} />
              <Metric label="Queue Items" value={healthQuery.data.queueCount} />
              <Metric label="Current Session" value={healthQuery.data.currentSessionId ?? "none"} />
            </div>
          ) : (
            <EmptyState title="health を取得できません" description={healthQuery.error instanceof Error ? healthQuery.error.message : undefined} />
          )}
        </Card>

        <Card className="mt-4">
          <SectionHeader eyebrow="Audit" title="Audit events" description="SSE 履歴由来の監査イベントを検索しながら確認します。" />
          <div className="space-y-3">
            <Input value={auditSearchText} onChange={(event) => setAuditSearchText(event.target.value)} placeholder="event type / summary を検索" />
            {auditEvents.length ? (
              <div className="space-y-3">
                {auditEvents.map((event) => (
                  <div key={event.id} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <div className="font-semibold text-slate-950">{event.eventType}</div>
                      <Badge tone="default">{event.id}</Badge>
                    </div>
                    <div className="mt-1 text-xs uppercase tracking-[0.18em] text-slate-500">{formatInstant(event.occurredAt)}</div>
                    <div className="mt-2 text-sm text-slate-600">{event.summary}</div>
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
        <div>Assets: {metrics.assetCount}</div>
        <div>Hits: {metrics.cacheHitCount}</div>
        <div>Rate: {formatPercent(metrics.cacheHitRate)}</div>
      </div>
    </div>
  );
}

function MonitorJobCard({ job }: { job: MonitorProviderJob }) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="font-semibold text-slate-950">{job.jobType}</div>
        <Badge tone={job.status === "RUNNING" ? "warning" : job.status === "FAILED" ? "danger" : "default"}>{job.status}</Badge>
        <Badge tone="default">{job.providerType}</Badge>
        {job.providerKey ? <Badge tone="accent">{job.providerKey}</Badge> : null}
      </div>
      <div className="mt-2 text-sm text-slate-600">{job.queueItemId ?? "no queue item"}</div>
      <div className="mt-1 text-xs text-slate-500">
        {formatInstant(job.updatedAt)}
        {job.errorCode || job.externalRef ? " / " : ""}
        {job.errorCode ? `error: ${job.errorCode}` : null}
        {job.errorCode && job.externalRef ? " / " : ""}
        {job.externalRef ? `ref: ${job.externalRef}` : ""}
      </div>
    </div>
  );
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
