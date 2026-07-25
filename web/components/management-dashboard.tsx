"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { getManagementDashboard } from "@/lib/api";
import { Badge, Card, EmptyState, Metric, SectionHeader } from "@/components/ui";
import { PanelColumn, PanelGrid } from "@/components/markdown";
import type { PreGenerationRequestStatus, ProviderHealthPayload } from "@/lib/types";

export function ManagementDashboard() {
  const dashboardQuery = useQuery({
    queryKey: ["management-dashboard"],
    queryFn: getManagementDashboard,
    refetchInterval: 15_000,
  });

  if (dashboardQuery.isLoading) {
    return <EmptyState title="管理ダッシュボードを読み込んでいます" description="システム状態と局別データ量を集計しています。" />;
  }
  if (dashboardQuery.error || !dashboardQuery.data) {
    return <EmptyState title="管理ダッシュボードを取得できませんでした" description="管理セッションと Server の状態を確認して、再読み込みしてください。" />;
  }

  const dashboard = dashboardQuery.data;
  const system = dashboard.system;
  const downProviders = Object.values(system.providerHealth).filter((provider) => provider.status === "DOWN");

  return (
    <PanelGrid>
      <PanelColumn className="xl:col-span-12">
        <Card tone={system.degraded || downProviders.length > 0 ? "warning" : "accent"} elevation="raised">
          <SectionHeader
            eyebrow="System overview"
            title="システム全体の状況"
            description="放送、生成 Provider、キュー、キャッシュ、局別コンテンツを管理画面の入口で確認できます。"
            action={<Badge tone={system.degraded ? "warning" : "success"}>{system.state}</Badge>}
          />
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-6">
            <Metric label="稼働局" value={`${dashboard.activeStationCount} / ${dashboard.stationCount}`} tone="accent" />
            <Metric label="番組テンプレート" value={`${dashboard.programTemplateCount} 件`} />
            <Metric label="READY バッファ" value={`${system.bufferReadyCount} 件`} tone={system.bufferReadyCount > 0 ? "success" : "warning"} />
            <Metric label="生成ジョブ" value={`${system.runningJobs.length} 件実行中`} />
            <Metric label="生成データ" value={formatBytes(system.cache.byteSize)} />
            <Metric label="直近エラー" value={`${system.recentErrors.length} 件`} tone={system.recentErrors.length > 0 ? "warning" : "success"} />
          </div>
        </Card>

        <div className="grid gap-4 xl:grid-cols-2">
          <Card>
            <SectionHeader
              eyebrow="Providers"
              title="AI・音声・音楽生成"
              description="接続先ごとの最新状態です。DOWN の Provider があっても、fallback により放送継続を優先します。"
            />
            <div className="space-y-3">
              {Object.entries(system.providerHealth).map(([key, provider]) => (
                <ProviderRow key={key} name={key} provider={provider} />
              ))}
              {Object.keys(system.providerHealth).length === 0 ? (
                <EmptyState title="Provider 状態がありません" description="接続設定を保存して接続確認を実行してください。" />
              ) : null}
            </div>
          </Card>

          <Card>
            <SectionHeader
              eyebrow="Actions"
              title="次に確認する項目"
              description="全体状況から、詳細確認やデータ生成へ直接移動できます。"
            />
            <div className="grid gap-3 sm:grid-cols-2">
              <DashboardLink href="/settings/content" title="コンテンツ管理" description="局別の番組・音声・曲の保有量を確認し、事前生成を依頼します。" />
              <DashboardLink href="/monitor" title="監視詳細" description="Provider job、監査イベント、asset 整合性を確認します。" />
              <DashboardLink href="/settings/stations" title="局管理" description="局名、人格、音声、有効状態を編集します。" />
              <DashboardLink href="/settings/programming" title="番組編成" description="番組テンプレートと局別ポリシーを編集します。" />
            </div>
          </Card>
        </div>

        <Card>
          <SectionHeader
            eyebrow="Station inventory"
            title="局別コンテンツ保有量"
            description="番組 block、事前生成済み番組、台本・音声・曲 asset を局単位で集計しています。"
            action={<Link className="text-sm font-bold text-teal-800 hover:text-teal-600" href="/settings/content">詳細と事前生成 →</Link>}
          />
          <div className="overflow-x-auto">
            <table className="min-w-full text-left text-sm">
              <thead className="text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="px-3 py-2">局</th>
                  <th className="px-3 py-2">番組</th>
                  <th className="px-3 py-2">事前生成</th>
                  <th className="px-3 py-2">音声</th>
                  <th className="px-3 py-2">曲</th>
                  <th className="px-3 py-2">保存量</th>
                  <th className="px-3 py-2">直近処理</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200">
                {dashboard.stations.map((station) => (
                  <tr key={station.stationId}>
                    <td className="px-3 py-3 font-semibold text-slate-950">{station.stationName}</td>
                    <td className="px-3 py-3">{station.programCount} 件</td>
                    <td className="px-3 py-3">{station.preGeneratedProgramCount} 件</td>
                    <td className="px-3 py-3">{station.audioAssetCount} 件</td>
                    <td className="px-3 py-3">{station.musicAssetCount} 件</td>
                    <td className="px-3 py-3">{formatBytes(station.generatedAssetBytes)}</td>
                    <td className="px-3 py-3">
                      {station.latestPreGeneration ? (
                        <Badge tone={preGenerationTone(station.latestPreGeneration.status)}>{station.latestPreGeneration.status}</Badge>
                      ) : (
                        <span className="text-slate-500">未実行</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      </PanelColumn>
    </PanelGrid>
  );
}

function ProviderRow({ name, provider }: { name: string; provider: ProviderHealthPayload }) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
      <div>
        <p className="font-semibold text-slate-950">{name}</p>
        <p className="mt-1 text-xs text-slate-500">{provider.providerKey ?? "未選択"} · {provider.message}</p>
      </div>
      <Badge tone={provider.status === "UP" ? "success" : provider.status === "DEGRADED" ? "warning" : "danger"}>
        {provider.status}
      </Badge>
    </div>
  );
}

function DashboardLink({ href, title, description }: { href: string; title: string; description: string }) {
  return (
    <Link href={href} className="interactive-control rounded-2xl border border-slate-200 bg-white p-4 hover:border-teal-400">
      <span className="block font-bold text-slate-950">{title}</span>
      <span className="mt-2 block text-sm leading-6 text-slate-600">{description}</span>
    </Link>
  );
}

export function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value.toFixed(value >= 10 ? 1 : 2)} ${units[index]}`;
}

function preGenerationTone(status: PreGenerationRequestStatus): "default" | "success" | "warning" | "danger" | "accent" {
  if (status === "MATERIALIZED") return "success";
  if (status === "FAILED") return "danger";
  if (status === "RUNNING") return "warning";
  return "accent";
}
