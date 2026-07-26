"use client";

import { useDeferredValue, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getMonitorSummary, getOperationalLogs } from "@/lib/api";
import { formatSafeDisplayText } from "@/lib/safe-metadata";
import type { MonitorProviderJob, OperationalEvent } from "@/lib/types";
import { Badge, Card, EmptyState, Input, Metric, SectionHeader } from "@/components/ui";

const REFRESH_INTERVAL_MS = 5_000;
const LEVEL_FILTERS = ["ALL", "ERROR", "WARN", "INFO"] as const;
const CATEGORY_FILTERS = ["ALL", "PRE_GENERATION", "PROVIDER_JOB"] as const;

type LevelFilter = (typeof LEVEL_FILTERS)[number];
type CategoryFilter = (typeof CATEGORY_FILTERS)[number];

export function OperationalLogViewer() {
  const [level, setLevel] = useState<LevelFilter>("ALL");
  const [category, setCategory] = useState<CategoryFilter>("ALL");
  const [searchText, setSearchText] = useState("");
  const deferredSearchText = useDeferredValue(searchText.trim().toLowerCase());
  const logsQuery = useQuery({
    queryKey: ["monitor", "logs"],
    queryFn: () => getOperationalLogs(100),
    retry: false,
    refetchInterval: REFRESH_INTERVAL_MS,
    refetchOnWindowFocus: false,
  });
  const jobsQuery = useQuery({
    queryKey: ["monitor", "summary"],
    queryFn: getMonitorSummary,
    retry: false,
    refetchInterval: REFRESH_INTERVAL_MS,
    refetchOnWindowFocus: false,
  });
  const logs = filterOperationalEvents(logsQuery.data ?? [], level, category, deferredSearchText);
  const recentJobs = jobsQuery.data?.recentJobs ?? [];
  const runningJobCount = recentJobs.filter((job) => job.status === "RUNNING" || job.status === "QUEUED").length;
  const succeededJobCount = recentJobs.filter((job) => job.status === "SUCCEEDED").length;
  const failedJobCount = recentJobs.filter((job) => job.status === "FAILED" || job.status === "CANCELLED").length;

  return (
    <Card>
      <SectionHeader
        eyebrow="監視・運用ログ"
        title="コンテンツ生成とジョブ実行状況"
        description="SSE の更新通知と5秒間隔の再取得で、生成ジョブの開始・成功・失敗を追跡します。秘密値、プロンプト、レター本文、生成本文は保存・表示しません。"
      />

      <section className="mb-6 space-y-3" aria-live="polite">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-base font-semibold text-slate-950">直近の生成ジョブ</h2>
          <span className="text-xs text-slate-500">
            {jobsQuery.isFetching ? "更新中…" : `最終更新: ${formatQueryUpdatedAt(jobsQuery.dataUpdatedAt)}`}
          </span>
        </div>
        <div className="grid gap-3 sm:grid-cols-3">
          <Metric label="待機・実行中" value={runningJobCount} tone={runningJobCount > 0 ? "accent" : "default"} />
          <Metric label="成功" value={succeededJobCount} tone={succeededJobCount > 0 ? "success" : "default"} />
          <Metric label="失敗・中止" value={failedJobCount} tone={failedJobCount > 0 ? "warning" : "default"} />
        </div>
        {jobsQuery.isError ? (
          <EmptyState
            title="生成ジョブの状態を取得できません"
            description={jobsQuery.error instanceof Error ? formatSafeDisplayText(jobsQuery.error.message) : "管理セッションと Server API を確認してください。"}
          />
        ) : recentJobs.length ? (
          <div className="grid gap-3 xl:grid-cols-2">
            {recentJobs.slice(0, 10).map((job) => <ProviderJobCard key={job.id} job={job} />)}
          </div>
        ) : (
          <EmptyState title="記録された生成ジョブはありません" description="生成処理を開始すると、待機・実行中・成功・失敗がここに表示されます。" />
        )}
      </section>

      <h2 className="mb-3 text-base font-semibold text-slate-950">運用イベント</h2>
      <div className="mb-4 grid gap-3 xl:grid-cols-[minmax(0,1fr)_auto_auto] xl:items-center">
        <Input
          value={searchText}
          onChange={(event) => setSearchText(event.target.value)}
          placeholder="エラーコード / Provider / request ID / correlation ID を検索"
          aria-label="運用ログを検索"
        />
        <FilterButtons
          label="レベル"
          values={LEVEL_FILTERS}
          selected={level}
          onSelect={(value) => setLevel(value as LevelFilter)}
        />
        <FilterButtons
          label="カテゴリー"
          values={CATEGORY_FILTERS}
          selected={category}
          onSelect={(value) => setCategory(value as CategoryFilter)}
        />
      </div>

      {logsQuery.isError ? (
        <EmptyState
          title="運用ログを取得できません"
          description={logsQuery.error instanceof Error ? formatSafeDisplayText(logsQuery.error.message) : "管理セッションと Server API を確認してください。"}
        />
      ) : logs.length ? (
        <div className="space-y-3" aria-live="polite">
          {logs.map((event) => (
            <OperationalEventCard key={event.id} event={event} />
          ))}
        </div>
      ) : (
        <EmptyState
          title="条件に一致する運用ログはありません"
          description="フィルターまたは検索文字列を変更してください。"
        />
      )}
    </Card>
  );
}

export function filterOperationalEvents(
  events: OperationalEvent[],
  level: string,
  category: string,
  searchText: string,
) {
  return events.filter((event) => {
    if (level !== "ALL" && event.level !== level) return false;
    if (category !== "ALL" && event.category !== category) return false;
    if (!searchText) return true;
    return [
      event.eventType,
      event.sourceId ?? "",
      event.correlationId ?? "",
      event.providerType ?? "",
      event.providerKey ?? "",
      event.errorCode ?? "",
      event.message,
    ].join(" ").toLowerCase().includes(searchText);
  });
}

function OperationalEventCard({ event }: { event: OperationalEvent }) {
  return (
    <article className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={levelTone(event.level)}>{levelLabel(event.level)}</Badge>
        <Badge tone="default">{categoryLabel(event.category)}</Badge>
        <span className="font-semibold text-slate-950">{eventTypeLabel(event.eventType)}</span>
        {event.providerKey ? <Badge tone="accent">{event.providerKey}</Badge> : null}
        {event.errorCode ? <Badge tone="danger">{event.errorCode}</Badge> : null}
      </div>
      <p className="mt-3 text-sm leading-6 text-slate-700">{formatSafeDisplayText(event.message)}</p>
      <dl className="mt-3 grid gap-2 text-xs text-slate-500 md:grid-cols-2 xl:grid-cols-4">
        <LogDetail label="発生日時" value={formatInstant(event.occurredAt)} />
        <LogDetail label="発生元 ID" value={event.sourceId ?? "-"} />
        <LogDetail label="相関 ID" value={event.correlationId ?? "-"} />
        <LogDetail label="Provider 種別" value={providerTypeLabel(event.providerType)} />
      </dl>
    </article>
  );
}

function ProviderJobCard({ job }: { job: MonitorProviderJob }) {
  return (
    <article className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={jobStatusTone(job.status)}>{jobStatusLabel(job.status)}</Badge>
        <span className="font-semibold text-slate-950">{jobTypeLabel(job.jobType)}</span>
        <Badge tone="default">{providerTypeLabel(job.providerType)}</Badge>
        {job.providerKey ? <Badge tone="accent">{job.providerKey}</Badge> : null}
        {job.errorCode ? <Badge tone="danger">{job.errorCode}</Badge> : null}
      </div>
      <dl className="mt-3 grid gap-2 text-xs text-slate-500 md:grid-cols-2">
        <LogDetail label="ジョブ ID" value={job.id} />
        <LogDetail label="キュー項目 ID" value={job.queueItemId ?? "-"} />
        <LogDetail label="開始日時" value={job.startedAt ? formatInstant(job.startedAt) : "未開始"} />
        <LogDetail
          label={job.endedAt ? "完了日時" : "最終更新"}
          value={formatInstant(job.endedAt ?? job.updatedAt)}
        />
      </dl>
    </article>
  );
}

function FilterButtons({
  label,
  values,
  selected,
  onSelect,
}: {
  label: string;
  values: readonly string[];
  selected: string;
  onSelect: (value: string) => void;
}) {
  return (
    <fieldset className="flex flex-wrap gap-2">
      <legend className="sr-only">{label}</legend>
      {values.map((value) => (
        <button
          key={value}
          type="button"
          onClick={() => onSelect(value)}
          aria-pressed={selected === value}
          className={`interactive-control rounded-full border px-3 py-1.5 text-xs font-semibold ${
            selected === value ? "border-slate-950 bg-slate-950 text-white" : "border-slate-200 bg-white text-slate-700"
          }`}
        >
          {filterValueLabel(value)}
        </button>
      ))}
    </fieldset>
  );
}

function filterValueLabel(value: string) {
  const labels: Record<string, string> = {
    ALL: "すべて",
    ERROR: "エラー",
    WARN: "警告",
    INFO: "情報",
    PRE_GENERATION: "事前生成",
    PROVIDER_JOB: "Provider ジョブ",
  };
  return labels[value] ?? value;
}

function levelLabel(level: OperationalEvent["level"]) {
  return filterValueLabel(level);
}

function categoryLabel(category: string) {
  return filterValueLabel(category);
}

export function eventTypeLabel(eventType: string) {
  const labels: Record<string, string> = {
    "provider.job.queued": "生成ジョブを受け付けました",
    "provider.job.running": "生成ジョブを実行中です",
    "provider.job.succeeded": "生成ジョブに成功しました",
    "provider.job.failed": "生成ジョブに失敗しました",
    "pre-generation.failed": "事前生成に失敗しました",
  };
  return labels[eventType] ?? eventType;
}

export function jobStatusLabel(status: MonitorProviderJob["status"]) {
  const labels: Record<MonitorProviderJob["status"], string> = {
    QUEUED: "待機中",
    RUNNING: "実行中",
    SUCCEEDED: "成功",
    FAILED: "失敗",
    CANCELLED: "中止",
  };
  return labels[status];
}

function jobTypeLabel(jobType: MonitorProviderJob["jobType"]) {
  const labels: Record<MonitorProviderJob["jobType"], string> = {
    SCRIPT_GEN: "台本生成",
    TTS_GEN: "音声生成",
    MUSIC_GEN: "楽曲生成",
  };
  return labels[jobType];
}

function providerTypeLabel(providerType: string | null) {
  if (!providerType) return "-";
  const labels: Record<string, string> = {
    LLM: "LLM",
    TTS: "音声合成",
    MUSIC: "楽曲生成",
  };
  return labels[providerType] ?? providerType;
}

function jobStatusTone(status: MonitorProviderJob["status"]): "default" | "accent" | "success" | "warning" | "danger" {
  if (status === "SUCCEEDED") return "success";
  if (status === "RUNNING") return "accent";
  if (status === "FAILED") return "danger";
  if (status === "CANCELLED") return "warning";
  return "default";
}

function LogDetail({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="font-semibold uppercase tracking-[0.14em] text-slate-400">{label}</dt>
      <dd className="mt-1 break-all text-slate-600">{value}</dd>
    </div>
  );
}

function levelTone(level: OperationalEvent["level"]): "default" | "warning" | "danger" {
  if (level === "ERROR") return "danger";
  if (level === "WARN") return "warning";
  return "default";
}

function formatInstant(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("ja-JP", {
    dateStyle: "short",
    timeStyle: "medium",
  }).format(date);
}

function formatQueryUpdatedAt(value: number) {
  if (!value) return "未取得";
  return formatInstant(new Date(value).toISOString());
}
