"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { getSettings, testConnections } from "@/lib/api";
import { Badge, Button, Card, EmptyState, JsonBlock, Metric, SectionHeader } from "@/components/ui";
import { PanelColumn, PanelGrid } from "@/components/markdown";

export function SettingsDashboard() {
  const settingsQuery = useQuery({
    queryKey: ["settings"],
    queryFn: getSettings,
    retry: false,
  });

  const connectionsMutation = useMutation({
    mutationFn: testConnections,
  });

  return (
    <PanelGrid>
      <PanelColumn className="xl:col-span-7">
        <Card>
          <SectionHeader
            eyebrow="Settings"
            title="Runtime configuration"
            description="現時点では編集 UI ではなく、`/api/settings` の内容を安全に閲覧できる初期画面です。"
            action={
              <Button tone="secondary" onClick={() => connectionsMutation.mutate()} disabled={connectionsMutation.isPending}>
                Test Connections
              </Button>
            }
          />
          {settingsQuery.data ? (
            <div className="space-y-4">
              <div className="grid gap-3 md:grid-cols-4">
                <Metric label="Version" value={settingsQuery.data.version} />
                <Metric label="Schema" value={settingsQuery.data.schemaVersion} />
                <Metric label="Server" value={`${settingsQuery.data.server.host}:${settingsQuery.data.server.port}`} />
                <Metric label="Config Path" value={settingsQuery.data.configPath} />
              </div>
              <div className="grid gap-4 lg:grid-cols-2">
                <Card tone="accent" className="p-4">
                  <div className="text-xs font-semibold uppercase tracking-[0.2em] text-teal-700">Playout</div>
                  <JsonBlock value={settingsQuery.data.playout} />
                </Card>
                <Card className="p-4">
                  <div className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500">Cache</div>
                  <JsonBlock value={settingsQuery.data.cache} />
                </Card>
              </div>
              <Card className="p-4">
                <div className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500">Providers</div>
                <JsonBlock value={settingsQuery.data.providers} />
              </Card>
            </div>
          ) : (
            <EmptyState
              title="設定を取得できません"
              description={settingsQuery.error instanceof Error ? settingsQuery.error.message : "管理トークンの設定を確認してください。"}
            />
          )}
        </Card>
      </PanelColumn>

      <PanelColumn className="xl:col-span-5">
        <Card>
          <SectionHeader eyebrow="Health" title="Provider connection test" description="`/api/settings/test-connections` を都度実行して結果を表示します。" />
          {connectionsMutation.data ? (
            <div className="space-y-3">
              {Object.entries(connectionsMutation.data.providers).map(([key, health]) => (
                <div key={key} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <div className="font-semibold text-slate-950">{key}</div>
                    <Badge tone={health.status === "UP" ? "success" : health.status === "DEGRADED" ? "warning" : "danger"}>
                      {health.status}
                    </Badge>
                  </div>
                  <div className="mt-2 text-sm text-slate-600">{health.message}</div>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState
              title="接続テストはまだです"
              description={connectionsMutation.error instanceof Error ? connectionsMutation.error.message : "右上のボタンで実行できます。"}
            />
          )}
        </Card>
      </PanelColumn>
    </PanelGrid>
  );
}
