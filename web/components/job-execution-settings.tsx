"use client";

import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiRequestError, getJobExecutionStatus, getSettings, updateSettings } from "@/lib/api";
import type { JobExecutionPolicy, JobExecutionSettings, SettingsResponse, SettingsUpdateRequest } from "@/lib/types";
import { Badge, Button, Card, EmptyState, Input, Label, Metric, SectionHeader } from "@/components/ui";
import { PanelColumn, PanelGrid } from "@/components/markdown";

const SELECT_CLASS_NAME =
  "field-control min-h-11 w-full rounded-2xl border border-slate-300 bg-white/90 px-4 py-3 text-sm text-slate-900 outline-none";

export function JobExecutionSettingsPanel() {
  const queryClient = useQueryClient();
  const settingsQuery = useQuery({
    queryKey: ["settings"],
    queryFn: getSettings,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const statusQuery = useQuery({
    queryKey: ["job-execution-status"],
    queryFn: getJobExecutionStatus,
    retry: false,
    refetchInterval: 3_000,
  });
  const [draft, setDraft] = useState<JobExecutionSettings | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (settingsQuery.data) {
      setDraft(cloneJobExecutionSettings(settingsQuery.data.features.jobExecution));
    }
  }, [settingsQuery.data]);

  const saveMutation = useMutation({
    mutationFn: (body: SettingsUpdateRequest) => updateSettings(body),
    onSuccess: (saved) => {
      queryClient.setQueryData(["settings"], saved);
      setDraft(cloneJobExecutionSettings(saved.features.jobExecution));
      setNotice("ジョブ実行設定を保存しました。新しく実行権を取得するジョブから反映されます。");
      void queryClient.invalidateQueries({ queryKey: ["job-execution-status"] });
    },
  });

  if (settingsQuery.isLoading || !draft) {
    return <EmptyState title="ジョブ実行設定を読み込んでいます" />;
  }
  if (!settingsQuery.data || settingsQuery.error) {
    return <EmptyState title="ジョブ実行設定を取得できませんでした" description="管理 session と Server 接続を確認してください。" />;
  }

  const status = statusQuery.data;
  const save = () => {
    setNotice(null);
    saveMutation.mutate(toUpdateRequest(settingsQuery.data, draft));
  };

  return (
    <PanelGrid>
      <PanelColumn className="xl:col-span-12">
        <Card tone="accent">
          <SectionHeader
            eyebrow="GPU job coordinator"
            title="ジョブ実行制御"
            description="Ollama と ACE-Step が同じ GPU を共有する環境で、手動・自動ジョブの実行権、Provider のアイドル待機、モデルロードと生成完了のタイムアウトを管理します。"
          />
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
            <Metric label="実行状態" value={status?.phase ?? "UNKNOWN"} />
            <Metric label="処理種別" value={status?.workload ?? "なし"} />
            <Metric label="起動元" value={status?.origin ?? "なし"} />
            <Metric label="待機ジョブ" value={`${status?.waitingJobs ?? 0} 件`} />
            <Metric label="直近結果" value={status?.lastOutcome ?? "UNKNOWN"} />
          </div>
          {status?.providerKey ? (
            <p className="mt-4 text-sm text-teal-950">
              実行中 Provider: <span className="font-semibold">{status.providerKey}</span>
            </p>
          ) : null}
        </Card>

        <Card>
          <SectionHeader
            eyebrow="Shared resource"
            title="共有 GPU の扱い"
            description="単一 GPU モードでは Server 内の LLM・音楽生成・ACE-Step モデルロードを同じ resource group で直列化します。"
          />
          <div className="grid gap-5 lg:grid-cols-2">
            <label className="flex items-start gap-3 rounded-2xl border border-slate-200 p-4 text-sm">
              <input
                type="checkbox"
                checked={draft.singleGpuMode}
                onChange={(event) => setDraft({ ...draft, singleGpuMode: event.target.checked })}
              />
              <span>
                <strong className="block text-slate-950">単一 GPU モード</strong>
                <span className="text-slate-600">LLM と音楽生成を同時実行せず、実行権を取得してから Provider を呼び出します。</span>
              </span>
            </label>
            <label className="flex items-start gap-3 rounded-2xl border border-slate-200 p-4 text-sm">
              <input
                type="checkbox"
                checked={draft.requireAceStepCpuOffload}
                onChange={(event) => setDraft({ ...draft, requireAceStepCpuOffload: event.target.checked })}
              />
              <span>
                <strong className="block text-slate-950">ACE-Step CPU オフロードを運用前提にする</strong>
                <span className="text-slate-600">ACE-Step v0.1.8 は汎用アンロード API を公開していないため、単一 GPU では配備側の CPU オフロードが必要です。</span>
              </span>
            </label>
            <div>
              <Label htmlFor="job-resource-group">resource group</Label>
              <Input
                id="job-resource-group"
                value={draft.resourceGroup}
                maxLength={64}
                onChange={(event) => setDraft({ ...draft, resourceGroup: event.target.value })}
              />
            </div>
            <div className="rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm leading-6 text-amber-950">
              <Badge tone={draft.singleGpuMode ? "warning" : "default"}>
                {draft.singleGpuMode ? "直列実行" : "並列実行"}
              </Badge>
              <p className="mt-2">
                音楽生成前は Ollama を <code>keep_alive=0</code> でアンロードし、LLM 実行前は ACE-Step の queued/running が 0 になるまで待機します。
              </p>
            </div>
          </div>
        </Card>

        <div className="grid gap-4 xl:grid-cols-2">
          <PolicyEditor
            title="手動ジョブ"
            description="コンテンツ事前生成と管理画面からの ACE-Step モデルロードに適用します。"
            policy={draft.manual}
            onChange={(manual) => setDraft({ ...draft, manual })}
          />
          <PolicyEditor
            title="自動ジョブ"
            description="放送キューの先読みとバックグラウンド生成に適用します。"
            policy={draft.automatic}
            onChange={(automatic) => setDraft({ ...draft, automatic })}
          />
        </div>

        <Card tone="dark">
          <SectionHeader
            eyebrow="Deployment preconditions"
            title="配備側で必要な設定"
            description="SeedShiftRadio の排他制御外で直接実行される処理にも耐えられるよう、Provider コンテナ側にも上限を設定します。"
          />
          <div className="grid gap-4 text-sm leading-6 text-slate-200 lg:grid-cols-2">
            <div className="rounded-2xl border border-slate-700 p-4">
              <strong className="text-white">releases/ollama</strong>
              <p className="break-all text-xs text-slate-400">ssh://git@192.168.0.57:2222/releases/ollama.git</p>
              <p>OLLAMA_NUM_PARALLEL=1、OLLAMA_MAX_LOADED_MODELS=1、OLLAMA_LOAD_TIMEOUT は画面のモデルロード待機以上にします。</p>
            </div>
            <div className="rounded-2xl border border-slate-700 p-4">
              <strong className="text-white">releases/acestep</strong>
              <p className="break-all text-xs text-slate-400">ssh://git@192.168.0.57:2222/releases/acestep.git</p>
              <p>ACESTEP_OFFLOAD_TO_CPU=true、ACESTEP_OFFLOAD_DIT_TO_CPU=true、ACESTEP_LM_OFFLOAD_TO_CPU=true を単一 GPU 用の構成に設定します。</p>
            </div>
          </div>
        </Card>

        <div className="flex flex-wrap items-center justify-end gap-3">
          {notice ? <p role="status" className="mr-auto text-sm font-semibold text-emerald-700">{notice}</p> : null}
          {saveMutation.error ? <p role="alert" className="mr-auto text-sm font-semibold text-rose-700">{errorMessage(saveMutation.error)}</p> : null}
          <Button tone="primary" disabled={saveMutation.isPending || draft.resourceGroup.trim() === ""} onClick={save}>
            {saveMutation.isPending ? "保存しています…" : "ジョブ実行設定を保存"}
          </Button>
        </div>
      </PanelColumn>
    </PanelGrid>
  );
}

function PolicyEditor({
  title,
  description,
  policy,
  onChange,
}: {
  title: string;
  description: string;
  policy: JobExecutionPolicy;
  onChange: (policy: JobExecutionPolicy) => void;
}) {
  const setNumber = (field: keyof Pick<JobExecutionPolicy,
    "resourceWaitTimeoutSeconds" | "providerIdleTimeoutSeconds" | "modelLoadTimeoutSeconds" | "jobTimeoutSeconds" | "pollIntervalMillis"
  >, value: string) => {
    onChange({ ...policy, [field]: Math.max(1, Number(value) || 1) });
  };
  return (
    <Card>
      <SectionHeader eyebrow="Execution policy" title={title} description={description} />
      <div className="space-y-4">
        <div>
          <Label htmlFor={`${title}-wait-strategy`}>実行権が使用中の場合</Label>
          <select
            id={`${title}-wait-strategy`}
            className={SELECT_CLASS_NAME}
            value={policy.waitStrategy}
            onChange={(event) => onChange({ ...policy, waitStrategy: event.target.value as JobExecutionPolicy["waitStrategy"] })}
          >
            <option value="WAIT">空くまで待機</option>
            <option value="FAIL_FAST">すぐ失敗</option>
          </select>
        </div>
        <NumberField label="実行権待機 timeout（秒）" value={policy.resourceWaitTimeoutSeconds} onChange={(value) => setNumber("resourceWaitTimeoutSeconds", value)} />
        <NumberField label="Provider idle 待機 timeout（秒）" value={policy.providerIdleTimeoutSeconds} onChange={(value) => setNumber("providerIdleTimeoutSeconds", value)} />
        <NumberField label="モデルロード timeout（秒）" value={policy.modelLoadTimeoutSeconds} onChange={(value) => setNumber("modelLoadTimeoutSeconds", value)} />
        <NumberField label="生成ジョブ完了 timeout（秒）" value={policy.jobTimeoutSeconds} onChange={(value) => setNumber("jobTimeoutSeconds", value)} />
        <NumberField label="状態確認間隔（ミリ秒）" value={policy.pollIntervalMillis} min={100} max={30_000} onChange={(value) => setNumber("pollIntervalMillis", value)} />
        <label className="flex items-center gap-3 text-sm text-slate-700">
          <input
            type="checkbox"
            checked={policy.unloadOllamaBeforeMusic}
            onChange={(event) => onChange({ ...policy, unloadOllamaBeforeMusic: event.target.checked })}
          />
          音楽生成前に Ollama をアンロード
        </label>
        <label className="flex items-center gap-3 text-sm text-slate-700">
          <input
            type="checkbox"
            checked={policy.waitForAceStepIdleBeforeLlm}
            onChange={(event) => onChange({ ...policy, waitForAceStepIdleBeforeLlm: event.target.checked })}
          />
          LLM 実行前に ACE-Step の idle を待機
        </label>
      </div>
    </Card>
  );
}

function NumberField({
  label,
  value,
  min = 1,
  max,
  onChange,
}: {
  label: string;
  value: number;
  min?: number;
  max?: number;
  onChange: (value: string) => void;
}) {
  return (
    <div>
      <Label>{label}</Label>
      <Input type="number" min={min} max={max} value={value} onChange={(event) => onChange(event.target.value)} />
    </div>
  );
}

function cloneJobExecutionSettings(settings: JobExecutionSettings): JobExecutionSettings {
  return {
    ...settings,
    manual: { ...settings.manual },
    automatic: { ...settings.automatic },
  };
}

function toUpdateRequest(settings: SettingsResponse, jobExecution: JobExecutionSettings): SettingsUpdateRequest {
  return {
    version: settings.version,
    schemaVersion: settings.schemaVersion,
    server: settings.server,
    paths: settings.paths,
    playout: settings.playout,
    cache: settings.cache,
    programming: settings.programming,
    providers: settings.providers,
    security: settings.security,
    features: {
      ...settings.features,
      jobExecution,
    },
  };
}

function errorMessage(error: Error) {
  return error instanceof ApiRequestError ? error.message : "ジョブ実行設定を保存できませんでした。";
}
