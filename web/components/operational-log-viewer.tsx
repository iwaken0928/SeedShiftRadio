"use client";

import { useDeferredValue, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getOperationalLogs } from "@/lib/api";
import { formatSafeDisplayText } from "@/lib/safe-metadata";
import type { OperationalEvent } from "@/lib/types";
import { Badge, Card, EmptyState, Input, SectionHeader } from "@/components/ui";

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
  const logs = filterOperationalEvents(logsQuery.data ?? [], level, category, deferredSearchText);

  return (
    <Card>
      <SectionHeader
        eyebrow="Monitor / Logs"
        title="構造化運用ログ"
        description="生成失敗と Provider ジョブを5秒ごとに更新します。秘密値、prompt、レター本文、生成本文は保存・表示しません。"
      />

      <div className="mb-4 grid gap-3 xl:grid-cols-[minmax(0,1fr)_auto_auto] xl:items-center">
        <Input
          value={searchText}
          onChange={(event) => setSearchText(event.target.value)}
          placeholder="error code / provider / request ID / correlation ID を検索"
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
        <Badge tone={levelTone(event.level)}>{event.level}</Badge>
        <Badge tone="default">{event.category}</Badge>
        <span className="font-semibold text-slate-950">{event.eventType}</span>
        {event.providerKey ? <Badge tone="accent">{event.providerKey}</Badge> : null}
        {event.errorCode ? <Badge tone="danger">{event.errorCode}</Badge> : null}
      </div>
      <p className="mt-3 text-sm leading-6 text-slate-700">{formatSafeDisplayText(event.message)}</p>
      <dl className="mt-3 grid gap-2 text-xs text-slate-500 md:grid-cols-2 xl:grid-cols-4">
        <LogDetail label="Occurred" value={formatInstant(event.occurredAt)} />
        <LogDetail label="Source ID" value={event.sourceId ?? "-"} />
        <LogDetail label="Correlation ID" value={event.correlationId ?? "-"} />
        <LogDetail label="Provider type" value={event.providerType ?? "-"} />
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
          {value}
        </button>
      ))}
    </fieldset>
  );
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
