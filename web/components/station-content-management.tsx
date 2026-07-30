"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ApiRequestError,
  deleteProgramContent,
  deleteStationContent,
  getManagementDashboard,
  getStationPrograms,
  listProgramTemplates,
  requestPreGeneration,
} from "@/lib/api";
import type { GeneratedAssetType, ProgramContentDetail } from "@/lib/types";
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
  const [programBlockId, setProgramBlockId] = useState("");

  const programsQuery = useQuery({
    queryKey: ["station-program-content", stationId],
    queryFn: () => getStationPrograms(stationId),
    enabled: Boolean(stationId),
    refetchInterval: 10_000,
  });

  useEffect(() => {
    if (!stationId && dashboardQuery.data?.stations.length) {
      setStationId(dashboardQuery.data.stations[0].stationId);
    }
  }, [dashboardQuery.data, stationId]);

  useEffect(() => {
    const programs = programsQuery.data?.programs ?? [];
    if (!programs.some((program) => program.programBlockId === programBlockId)) {
      setProgramBlockId(programs[0]?.programBlockId ?? "");
    }
  }, [programBlockId, programsQuery.data]);

  const selectedStation = dashboardQuery.data?.stations.find((station) => station.stationId === stationId) ?? null;
  const selectedProgram = programsQuery.data?.programs.find((program) => program.programBlockId === programBlockId) ?? null;
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
      await queryClient.invalidateQueries({ queryKey: ["station-program-content", stationId] });
    },
  });
  const deletionMutation = useMutation({
    mutationFn: () => deleteStationContent(stationId, { assetTypes: deletionAssetTypes }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["management-dashboard"] });
      await queryClient.invalidateQueries({ queryKey: ["station-program-content", stationId] });
    },
  });
  const programDeletionMutation = useMutation({
    mutationFn: () => deleteProgramContent(stationId, programBlockId, { assetTypes: deletionAssetTypes }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["management-dashboard"] });
      await queryClient.invalidateQueries({ queryKey: ["station-program-content", stationId] });
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
      `${selectedStation.stationName} の事前生成済み ${labels} を物理削除します。番組・queue・監査履歴は残し、queue の asset 参照を解除します。続行しますか？`,
    );
    if (confirmed) deletionMutation.mutate();
  };

  const confirmProgramDeletion = () => {
    if (!selectedProgram || !selectedProgram.preGenerated || deletionAssetTypes.length === 0) return;
    const labels = deletionAssetTypes.map((assetType) => assetTypeLabel(assetType)).join("・");
    const confirmed = window.confirm(
      `${selectedProgram.title} の事前生成済み ${labels} を物理削除します。番組とセグメントは残ります。続行しますか？`,
    );
    if (confirmed) programDeletionMutation.mutate();
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

        <Card>
          <SectionHeader
            eyebrow="By program"
            title={`${selectedStation?.stationName ?? "選択中の局"} の番組詳細`}
            description="局を起点に直近 100 番組を確認し、番組内のセグメント状態と生成 asset を管理します。事前生成番組は番組単位でも削除できます。"
          />
          {programsQuery.isLoading ? <EmptyState title="番組詳細を読み込んでいます" /> : null}
          {programsQuery.error ? (
            <p role="alert" className="text-sm font-semibold text-rose-700">番組詳細を取得できませんでした。</p>
          ) : null}
          {programsQuery.data && programsQuery.data.programs.length === 0 ? (
            <EmptyState title="番組データはまだありません" description="事前生成を実行するか、局の放送を開始すると番組単位の詳細が表示されます。" />
          ) : null}
          {programsQuery.data?.programs.length ? (
            <div className="grid gap-4 xl:grid-cols-[minmax(300px,2fr)_minmax(0,5fr)]">
              <div className="max-h-[38rem] space-y-2 overflow-y-auto pr-1">
                {programsQuery.data.programs.map((program) => (
                  <button
                    key={program.programBlockId}
                    type="button"
                    className={`w-full rounded-2xl border p-4 text-left transition ${
                      program.programBlockId === programBlockId
                        ? "border-teal-400 bg-teal-50"
                        : "border-slate-200 bg-white hover:border-teal-200"
                    }`}
                    onClick={() => setProgramBlockId(program.programBlockId)}
                  >
                    <span className="flex items-start justify-between gap-3">
                      <strong className="text-sm text-slate-950">{program.title}</strong>
                      <Badge tone={program.status === "FAILED" ? "danger" : program.status === "DONE" ? "success" : "warning"}>
                        {program.status}
                      </Badge>
                    </span>
                    <span className="mt-2 block text-xs leading-5 text-slate-600">
                      {program.preGenerated ? "事前生成" : "通常放送"} · {program.segmentCount} セグメント · {program.generatedAssetCount} asset · {formatBytes(program.generatedAssetBytes)}
                    </span>
                    <span className="mt-1 block text-xs text-slate-500">{formatDateTime(program.startedAt)}</span>
                  </button>
                ))}
              </div>
              {selectedProgram ? (
                <ProgramDetails
                  program={selectedProgram}
                  deletionAssetTypes={deletionAssetTypes}
                  toggleDeletionAssetType={toggleDeletionAssetType}
                  deleting={programDeletionMutation.isPending}
                  deletionResult={programDeletionMutation.data?.deletedAssetCount ?? null}
                  deletionError={programDeletionMutation.error}
                  onDelete={confirmProgramDeletion}
                />
              ) : null}
            </div>
          ) : null}
        </Card>

        <Card className="border-rose-200 bg-rose-50">
          <SectionHeader
            eyebrow="Content cleanup"
            title="事前生成コンテンツを削除"
            description="選択中の局に属するオフエア事前生成 asset のレコードと payload を削除します。ライブ再生、番組 block、queue、Provider job、監査履歴、archive 対象は削除しません。"
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
                同じファイルを別 asset が共有している場合、最後の参照が残る間は実ファイルを保持します。選択した generated asset レコードは残さず削除し、queue の asset 参照は解除します。
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

function ProgramDetails({
  program,
  deletionAssetTypes,
  toggleDeletionAssetType,
  deleting,
  deletionResult,
  deletionError,
  onDelete,
}: {
  program: ProgramContentDetail;
  deletionAssetTypes: GeneratedAssetType[];
  toggleDeletionAssetType: (assetType: GeneratedAssetType) => void;
  deleting: boolean;
  deletionResult: number | null;
  deletionError: Error | null;
  onDelete: () => void;
}) {
  return (
    <div className="min-w-0 space-y-4">
      <div className="rounded-2xl border border-slate-200 bg-slate-50 p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-teal-700">
              {program.preGenerated ? "Pre-generated program" : "Live program"}
            </p>
            <h3 className="mt-1 text-lg font-bold text-slate-950">{program.title}</h3>
            <p className="mt-1 break-all text-xs text-slate-500">{program.programBlockId}</p>
          </div>
          <Badge tone={program.preGenerated ? "warning" : "default"}>
            {program.preGenerated ? "事前生成" : "通常放送"}
          </Badge>
        </div>
        <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Metric label="セグメント" value={`${program.segmentCount} 件`} />
          <Metric label="生成待ち / 実行中" value={`${program.plannedSegmentCount} / ${program.generatingSegmentCount}`} />
          <Metric label="準備完了 / 失敗" value={`${program.readySegmentCount} / ${program.failedSegmentCount}`} />
          <Metric label="保存量" value={formatBytes(program.generatedAssetBytes)} />
        </div>
      </div>

      <div className="overflow-x-auto rounded-2xl border border-slate-200">
        <table className="min-w-full text-left text-sm">
          <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-3 py-2">順番</th>
              <th className="px-3 py-2">セグメント</th>
              <th className="px-3 py-2">状態</th>
              <th className="px-3 py-2">生成元</th>
              <th className="px-3 py-2">asset</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-200">
            {program.segments.map((segment) => (
              <tr key={segment.queueItemId}>
                <td className="px-3 py-3">{segment.sequenceNo}</td>
                <td className="px-3 py-3">
                  <strong className="block text-slate-900">{segment.title}</strong>
                  <span className="text-xs text-slate-500">{segment.segmentType} / {segment.slotRole}</span>
                </td>
                <td className="px-3 py-3">
                  <Badge tone={segment.status === "FAILED" ? "danger" : segment.status === "READY" || segment.status === "DONE" ? "success" : "warning"}>
                    {segment.status}
                  </Badge>
                </td>
                <td className="px-3 py-3 text-xs text-slate-600">{segment.contentOrigin}</td>
                <td className="px-3 py-3">
                  <div className="flex flex-wrap gap-1">
                    {segment.assets.length
                      ? segment.assets.map((asset) => (
                          <span key={asset.assetId} className="rounded-full bg-slate-100 px-2 py-1 text-xs text-slate-700">
                            {assetTypeLabel(asset.assetType)} {formatBytes(asset.byteSize)}
                          </span>
                        ))
                      : <span className="text-xs text-slate-400">なし</span>}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {program.preGenerated ? (
        <div className="rounded-2xl border border-rose-200 bg-rose-50 p-4">
          <p className="text-sm font-semibold text-rose-950">この番組の生成データを削除</p>
          <div className="mt-3 flex flex-wrap gap-4">
            {(["SCRIPT", "AUDIO", "MUSIC"] as GeneratedAssetType[]).map((assetType) => (
              <label key={assetType} className="flex items-center gap-2 text-sm text-rose-950">
                <input
                  type="checkbox"
                  checked={deletionAssetTypes.includes(assetType)}
                  onChange={() => toggleDeletionAssetType(assetType)}
                />
                {assetTypeLabel(assetType)} ({selectedAssetCount(program, assetType)} 件)
              </label>
            ))}
          </div>
          <Button
            tone="danger"
            className="mt-4"
            disabled={deleting || deletionAssetTypes.length === 0}
            onClick={onDelete}
          >
            {deleting ? "削除しています…" : "この番組から選択データを削除"}
          </Button>
          {deletionResult !== null ? (
            <p role="status" className="mt-3 text-sm font-semibold text-rose-950">{deletionResult} 件を削除しました。</p>
          ) : null}
          {deletionError ? (
            <p role="alert" className="mt-3 text-sm font-semibold text-rose-950">{errorMessage(deletionError)}</p>
          ) : null}
        </div>
      ) : (
        <p className="rounded-2xl border border-slate-200 bg-slate-50 p-4 text-sm text-slate-600">
          通常放送の番組はこの画面の一括削除対象外です。再生履歴と archive の整合性を維持します。
        </p>
      )}
    </div>
  );
}

function errorMessage(error: Error) {
  if (error instanceof ApiRequestError) return error.message;
  return "管理操作を完了できませんでした。";
}

function formatDateTime(value: string | null) {
  return value ? new Date(value).toLocaleString("ja-JP") : "未記録";
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
