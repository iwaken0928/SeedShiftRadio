"use client";

import { useQuery } from "@tanstack/react-query";
import { getHealth, getMonitorSummary } from "@/lib/api";
import { PanelColumn, PanelGrid } from "@/components/markdown";
import { Badge, Card, EmptyState, Metric, SectionHeader } from "@/components/ui";
import { useUiStore } from "@/stores/ui-store";

export function MonitorDashboard() {
  const recentEvents = useUiStore((state) => state.recentEvents);

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

  return (
    <PanelGrid>
      <PanelColumn className="xl:col-span-7">
        <Card>
          <SectionHeader eyebrow="Monitor" title="Operational summary" description="provider health, buffer 状態, pending letters を 1 画面で確認します。" />
          {summaryQuery.data ? (
            <div className="space-y-4">
              <div className="grid gap-3 md:grid-cols-4">
                <Metric label="State" value={summaryQuery.data.state ?? "IDLE"} tone={summaryQuery.data.degraded ? "warning" : "default"} />
                <Metric label="Buffer Ready" value={summaryQuery.data.bufferReadyCount} tone="accent" />
                <Metric label="Pending Letters" value={summaryQuery.data.pendingLetterCount} />
                <Metric label="Station" value={summaryQuery.data.stationId ?? "none"} />
              </div>
              <div className="grid gap-3">
                {Object.entries(summaryQuery.data.providerHealth).map(([key, value]) => (
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
          <SectionHeader eyebrow="Events" title="Recent SSE events" description="SSE reconnect と状態遷移の確認用ログです。" />
          {recentEvents.length ? (
            <div className="space-y-3">
              {recentEvents.map((event) => (
                <div key={`${event.id ?? "none"}-${event.at}`} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <div className="font-semibold text-slate-950">{event.event}</div>
                    {event.id ? <Badge tone="default">{event.id}</Badge> : null}
                  </div>
                  <div className="mt-1 text-sm text-slate-600">{event.summary}</div>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title="SSE event はまだありません" description="接続後の `radio.status.changed` などがここに積まれます。" />
          )}
        </Card>
      </PanelColumn>
    </PanelGrid>
  );
}
