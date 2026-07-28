"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiRequestError, deleteStationContent, getManagementDashboard, listProgramTemplates, requestPreGeneration } from "@/lib/api";
import type { GeneratedAssetType } from "@/lib/types";
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
  const [deletionAssetTypes, setDeletionAssetTypes] = useState<GeneratedAssetType[]>(["SCRIPT", "AUDIO", "MUSIC"]);

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
  const deletionMutation = useMutation({
    mutationFn: () => deleteStationContent(stationId, { assetTypes: deletionAssetTypes }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["management-dashboard"] });
    },
  });

  const toggleDeletionAssetType = (assetType: GeneratedAssetType) => {
    setDeletionAssetTypes((current) =>
      current.includes(assetType)
        ? current.filter((candidate) => candidate !== assetType)
        : [...current, assetType],
    );
  };

  const confirmDeletion = () => {
    if (!selectedStation || deletionAssetTypes.length === 0) return;
    const labels = deletionAssetTypes.map((assetType) => assetTypeLabel(assetType)).join("・");
    const confirmed = window.confirm(
      `${selectedStation.stationName} の事前生成済み ${labels} を削除します。番組・queue・監査履歴は残ります。続行しますか？`,
    );
    if (confirmed) deletionMutation.mutate();
  };

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

        <Card className="border-rose-200 bg-rose-50">
          <SectionHeader
            eyebrow="Content cleanup"
            title="事前生成コンテンツを削除"
            description="選択中の局に属するオフエア事前生成 asset の payload を削除します。ライブ再生、番組 block、queue、Provider job、監査履歴、archive 対象は削除しません。"
          />
          <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-end">
            <div>
              <p className="text-sm font-semibold text-rose-950">
                対象局: {selectedStation?.stationName ?? "局を選択してください"}
              </p>
              <div className="mt-3 flex flex-wrap gap-4">
                {(["SCRIPT", "AUDIO", "MUSIC"] as GeneratedAssetType[]).map((assetType) => (
                  <label key={assetType} className="flex items-center gap-2 text-sm text-rose-950">
                    <input
                      type="checkbox"
                      checked={deletionAssetTypes.includes(assetType)}
                      onChange={() => toggleDeletionAssetType(assetType)}
                    />
                    {assetTypeLabel(assetType)} ({selectedAssetCount(selectedStation, assetType)} 件)
                  </label>
                ))}
              </div>
              <p className="mt-3 text-xs leading-6 text-rose-900">
                同じファイルを別 asset が共有している場合、最後の参照が残る間は実ファイルを保持します。削除済み metadata は参照整合性と監査のため tombstone として残ります。
              </p>
            </div>
            <Button
              tone="danger"
              disabled={!stationId || deletionAssetTypes.length === 0 || deletionMutation.isPending}
              onClick={confirmDeletion}
            >
              {deletionMutation.isPending ? "削除しています…" : "選択したデータを削除"}
            </Button>
          </div>
          {deletionMutation.data ? (
            <p role="status" className="mt-4 text-sm font-semibold text-rose-950">
              {deletionMutation.data.deletedAssetCount} 件を削除し、台帳容量を {formatBytes(deletionMutation.data.reclaimedBytes)} 減らしました。
              {deletionMutation.data.failedAssetCount > 0 ? ` ${deletionMutation.data.failedAssetCount} 件は削除できませんでした。` : ""}
            </p>
          ) : null}
          {deletionMutation.error ? (
            <p role="alert" className="mt-4 text-sm font-semibold text-rose-950">{errorMessage(deletionMutation.error)}</p>
          ) : null}
        </Card>
      </PanelColumn>
    </PanelGrid>
  );
}

function errorMessage(error: Error) {
  if (error instanceof ApiRequestError) return error.message;
  return "管理操作を完了できませんでした。";
}

function assetTypeLabel(assetType: GeneratedAssetType) {
  return {
    SCRIPT: "台本",
    AUDIO: "音声",
    MUSIC: "曲",
  }[assetType];
}

function selectedAssetCount(
  station: {
    scriptAssetCount: number;
    audioAssetCount: number;
    musicAssetCount: number;
  } | null,
  assetType: GeneratedAssetType,
) {
  if (!station) return 0;
  return {
    SCRIPT: station.scriptAssetCount,
    AUDIO: station.audioAssetCount,
    MUSIC: station.musicAssetCount,
  }[assetType];
}
