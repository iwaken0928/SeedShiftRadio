"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiRequestError, getManagementDashboard, listProgramTemplates, requestPreGeneration } from "@/lib/api";
import { Badge, Button, Card, EmptyState, Input, Label, Metric, SectionHeader } from "@/components/ui";
import { PanelColumn, PanelGrid } from "@/components/markdown";
import { formatBytes } from "@/components/management-dashboard";

export function StationContentManagement() {
  const queryClient = useQueryClient();
  const dashboardQuery = useQuery({
    queryKey: ["management-dashboard"],
    queryFn: getManagementDashboard,
    refetchInterval: 10_000,
  });
  const templatesQuery = useQuery({ queryKey: ["program-templates"], queryFn: listProgramTemplates });
  const [stationId, setStationId] = useState("");
  const [templateId, setTemplateId] = useState("");
  const [targetProgramCount, setTargetProgramCount] = useState(1);
  const [includeSpeech, setIncludeSpeech] = useState(true);
  const [includeMusic, setIncludeMusic] = useState(true);

  useEffect(() => {
    if (!stationId && dashboardQuery.data?.stations.length) {
      setStationId(dashboardQuery.data.stations[0].stationId);
    }
  }, [dashboardQuery.data, stationId]);

  const selectedStation = dashboardQuery.data?.stations.find((station) => station.stationId === stationId) ?? null;
  const applicableTemplates = useMemo(
    () => (templatesQuery.data ?? []).filter((template) =>
      template.isActive && (template.scope.toUpperCase() === "GLOBAL" || template.stationId === stationId)),
    [stationId, templatesQuery.data],
  );
  const generationMutation = useMutation({
    mutationFn: () => requestPreGeneration(stationId, {
      programTemplateId: templateId || null,
      targetProgramCount,
      includeSpeech,
      includeMusic,
    }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["management-dashboard"] });
    },
  });

  if (dashboardQuery.isLoading || templatesQuery.isLoading) {
    return <EmptyState title="コンテンツ台帳を読み込んでいます" />;
  }
  if (!dashboardQuery.data || dashboardQuery.error || templatesQuery.error) {
    return <EmptyState title="コンテンツ台帳を取得できませんでした" description="管理 session と Server 接続を確認してください。" />;
  }

  return (
    <PanelGrid>
      <PanelColumn className="xl:col-span-12">
        <Card tone="accent">
          <SectionHeader
            eyebrow="Content inventory"
            title="局別コンテンツ管理"
            description="局に紐づく番組 block、台本、音声、曲 asset の保有量を確認し、放送中のセッションとは別に事前生成できます。"
          />
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
            <Metric label="局" value={`${dashboardQuery.data.stationCount} 局`} />
            <Metric label="番組テンプレート" value={`${dashboardQuery.data.programTemplateCount} 件`} />
            <Metric label="番組データ" value={`${dashboardQuery.data.stations.reduce((sum, station) => sum + station.programCount, 0)} 件`} />
            <Metric label="曲データ" value={`${dashboardQuery.data.stations.reduce((sum, station) => sum + station.musicAssetCount, 0)} 件`} />
            <Metric label="総保存量" value={formatBytes(dashboardQuery.data.stations.reduce((sum, station) => sum + station.generatedAssetBytes, 0))} />
          </div>
        </Card>

        <div className="grid gap-4 xl:grid-cols-[minmax(0,5fr)_minmax(360px,3fr)]">
          <Card>
            <SectionHeader
              eyebrow="By station"
              title="保有データ一覧"
              description="事前生成セッションを含む番組と、そこから生成された asset を局単位で集計します。"
            />
            <div className="overflow-x-auto">
              <table className="min-w-full text-left text-sm">
                <thead className="text-xs uppercase tracking-wide text-slate-500">
                  <tr>
                    <th className="px-3 py-2">局</th>
                    <th className="px-3 py-2">テンプレート</th>
                    <th className="px-3 py-2">番組</th>
                    <th className="px-3 py-2">台本</th>
                    <th className="px-3 py-2">音声</th>
                    <th className="px-3 py-2">曲</th>
                    <th className="px-3 py-2">曲容量</th>
                    <th className="px-3 py-2">合計</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-200">
                  {dashboardQuery.data.stations.map((station) => (
                    <tr key={station.stationId} className={station.stationId === stationId ? "bg-teal-50/60" : undefined}>
                      <td className="px-3 py-3">
                        <button type="button" className="font-semibold text-teal-800 hover:text-teal-600" onClick={() => setStationId(station.stationId)}>
                          {station.stationName}
                        </button>
                      </td>
                      <td className="px-3 py-3">{station.applicableProgramTemplateCount}</td>
                      <td className="px-3 py-3">{station.programCount}</td>
                      <td className="px-3 py-3">{station.scriptAssetCount}</td>
                      <td className="px-3 py-3">{station.audioAssetCount}</td>
                      <td className="px-3 py-3">{station.musicAssetCount}</td>
                      <td className="px-3 py-3">{formatBytes(station.musicAssetBytes)}</td>
                      <td className="px-3 py-3">{formatBytes(station.generatedAssetBytes)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>

          <Card tone="dark">
            <SectionHeader
              eyebrow="Pre-generation"
              title="番組データを事前生成"
              description="選択した局と番組テンプレートからオフエア用の番組 block を作り、台本・音声・曲の生成ジョブを投入します。"
            />
            <div className="space-y-4">
              <div>
                <Label htmlFor="pregen-station">局</Label>
                <select id="pregen-station" className="field-control min-h-11 w-full rounded-2xl border border-slate-600 bg-slate-900 px-4 py-3 text-sm text-white" value={stationId} onChange={(event) => { setStationId(event.target.value); setTemplateId(""); }}>
                  {dashboardQuery.data.stations.map((station) => <option key={station.stationId} value={station.stationId}>{station.stationName}</option>)}
                </select>
              </div>
              <div>
                <Label htmlFor="pregen-template">番組テンプレート</Label>
                <select id="pregen-template" className="field-control min-h-11 w-full rounded-2xl border border-slate-600 bg-slate-900 px-4 py-3 text-sm text-white" value={templateId} onChange={(event) => setTemplateId(event.target.value)}>
                  <option value="">局の編成ルールから自動選択</option>
                  {applicableTemplates.map((template) => <option key={template.id} value={template.id}>{template.name} v{template.version}</option>)}
                </select>
              </div>
              <div>
                <Label htmlFor="pregen-count">生成する番組数（1〜10）</Label>
                <Input id="pregen-count" type="number" min={1} max={10} value={targetProgramCount} onChange={(event) => setTargetProgramCount(Math.max(1, Math.min(10, Number(event.target.value) || 1)))} />
              </div>
              <label className="flex items-center gap-3 text-sm text-slate-200">
                <input type="checkbox" checked={includeSpeech} onChange={(event) => setIncludeSpeech(event.target.checked)} />
                台本・音声を生成する
              </label>
              <label className="flex items-center gap-3 text-sm text-slate-200">
                <input type="checkbox" checked={includeMusic} onChange={(event) => setIncludeMusic(event.target.checked)} />
                曲を生成する
              </label>
              <div className="rounded-2xl border border-slate-700 bg-slate-900/70 p-4 text-xs leading-6 text-slate-300">
                この操作はライブの局切替、現在番組、再生キューを変更しません。音楽生成は非同期 Provider job として継続し、失敗時は監視画面へ分類済みエラーだけを表示します。
              </div>
              <Button tone="secondary" className="w-full" disabled={!stationId || generationMutation.isPending || (!includeSpeech && !includeMusic)} onClick={() => generationMutation.mutate()}>
                {generationMutation.isPending ? "事前生成を受け付けています…" : "事前生成を開始"}
              </Button>
              {generationMutation.isSuccess ? <p role="status" className="text-sm font-semibold text-emerald-300">事前生成を受け付けました。台帳は自動更新されます。</p> : null}
              {generationMutation.error ? <p role="alert" className="text-sm font-semibold text-rose-300">{errorMessage(generationMutation.error)}</p> : null}
              {selectedStation?.latestPreGeneration ? (
                <div className="flex items-center justify-between rounded-2xl border border-slate-700 px-4 py-3 text-sm">
                  <span>直近: {selectedStation.latestPreGeneration.materializedProgramCount} 番組 / {selectedStation.latestPreGeneration.queuedMusicCount} 曲 job</span>
                  <Badge tone={selectedStation.latestPreGeneration.status === "FAILED" ? "danger" : selectedStation.latestPreGeneration.status === "MATERIALIZED" ? "success" : "warning"}>
                    {selectedStation.latestPreGeneration.status}
                  </Badge>
                </div>
              ) : null}
            </div>
          </Card>
        </div>
      </PanelColumn>
    </PanelGrid>
  );
}

function errorMessage(error: Error) {
  if (error instanceof ApiRequestError) return error.message;
  return "事前生成を開始できませんでした。";
}
