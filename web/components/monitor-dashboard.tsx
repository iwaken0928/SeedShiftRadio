"use client";

import { useQuery } from "@tanstack/react-query";
import { getHealth, getMonitorSummary } from "@/lib/api";
import { PanelColumn, PanelGrid } from "@/components/markdown";
import { Badge, Card, EmptyState, Metric, SectionHeader } from "@/components/ui";
import type { MonitorProviderJob } from "@/lib/types";

export function MonitorDashboard() {
  const summaryQuery = useQuery({
    queryKey: ["monitor", "summary"],
    queryFn: getMonitorSummary,
    retry: false,
  });

  const healthQuery = useQuery({
    queryKey: ["health"],
    queryFn: getHealth,
    retry: false,
  });

  const summary = summaryQuery.data;

  return (
    <PanelGrid>
      <PanelColumn className="xl:col-span-7">
        <Card>
          <SectionHeader
            eyebrow="Monitor"
            title="Operational summary"
            description="provider health, buffer, pending letters, running jobs, recent errors, and audit events を 1 画面で確認します。"
          />
          {summary ? (
            <div className="space-y-6">
              <div className="grid gap-3 md:grid-cols-4">
                <Metric label="State" value={summary.state ?? "IDLE"} tone={summary.degraded ? "warning" : "default"} />
                <Metric label="Buffer Ready" value={summary.bufferReadyCount} tone="accent" />
                <Metric label="Pending Letters" value={summary.pendingLetterCount} />
                <Metric label="Station" value={summary.stationId ?? "none"} />
              </div>

              <section className="space-y-3">
                <SectionHeader eyebrow="Providers" title="Provider health" description="LLM / TTS / MusicGen の最新 health です。" />
                <div className="grid gap-3">
                  {Object.entries(summary.providerHealth).map(([key, value]) => (
                    <div key={key} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <div className="font-semibold text-slate-950">{key}</div>
                        <Badge tone={value.status === "UP" ? "success" : value.status === "DEGRADED" ? "warning" : "danger"}>
                          {value.status}
                        </Badge>
                        {value.providerKey ? <Badge tone="default">{value.providerKey}</Badge> : null}
                      </div>
                      <div className="mt-2 text-sm text-slate-600">{value.message}</div>
                    </div>
                  ))}
                </div>
              </section>

              <section className="space-y-3">
                <SectionHeader eyebrow="Jobs" title="Running jobs" description="現在進行中の provider ジョブです。" />
                {summary.runningJobs.length ? (
                  <div className="grid gap-3">
                    {summary.runningJobs.map((job) => (
                      <MonitorJobCard key={job.id} job={job} />
                    ))}
                  </div>
                ) : (
                  <EmptyState title="Running jobs はありません" />
                )}
              </section>

              <section className="space-y-3">
                <SectionHeader eyebrow="Errors" title="Recent errors" description="直近の provider 失敗を表示します。" />
                {summary.recentErrors.length ? (
                  <div className="grid gap-3">
                    {summary.recentErrors.map((job) => (
                      <MonitorJobCard key={job.id} job={job} />
                    ))}
                  </div>
                ) : (
                  <EmptyState title="Recent errors はありません" />
                )}
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
          <SectionHeader eyebrow="Audit" title="Audit events" description="SSE 履歴から直近の監査イベントをまとめて表示します。" />
          {summary?.auditEvents.length ? (
            <div className="space-y-3">
              {summary.auditEvents.map((event) => (
                <div key={event.id} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <div className="font-semibold text-slate-950">{event.eventType}</div>
                    <Badge tone="default">{event.id}</Badge>
                  </div>
                  <div className="mt-1 text-xs uppercase tracking-[0.18em] text-slate-500">{event.occurredAt}</div>
                  <div className="mt-2 text-sm text-slate-600">{event.summary}</div>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title="Audit events はまだありません" description="`radio.status.changed` や `provider.job.failed` がここに出ます。" />
          )}
        </Card>
      </PanelColumn>
    </PanelGrid>
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
        {job.updatedAt}
        {job.errorCode || job.externalRef ? " / " : ""}
        {job.errorCode ? `error: ${job.errorCode}` : null}
        {job.errorCode && job.externalRef ? " / " : ""}
        {job.externalRef ? `ref: ${job.externalRef}` : ""}
      </div>
    </div>
  );
}
