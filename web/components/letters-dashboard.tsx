"use client";

import { useDeferredValue, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createLetter, getLetter, getRadioStatus, listLetters, lookupLetterPublicHistory, replyLetter, updateLetterStatus } from "@/lib/api";
import { getAdminToken } from "@/lib/env";
import { PanelColumn, PanelGrid } from "@/components/markdown";
import { Badge, Button, Card, EmptyState, Input, SectionHeader, Textarea } from "@/components/ui";
import { useUiStore } from "@/stores/ui-store";
import type { LetterPublicSummary, LetterStatus, LetterSubmissionRecord } from "@/lib/types";

const letterStatuses: LetterStatus[] = ["UNREAD", "PENDING", "ADOPTED", "REPLIED"];
const MAX_SUBJECT_LENGTH = 255;
const MAX_BODY_LENGTH = 1000;

export function LettersDashboard() {
  const queryClient = useQueryClient();
  const hasAdminToken = Boolean(getAdminToken());
  const selectedStationId = useUiStore((state) => state.selectedStationId);
  const radioName = useUiStore((state) => state.radioName);
  const setRadioName = useUiStore((state) => state.setRadioName);
  const localLetterSubmissions = useUiStore((state) => state.localLetterSubmissions);
  const addLocalLetterSubmission = useUiStore((state) => state.addLocalLetterSubmission);
  const [selectedStatus, setSelectedStatus] = useState<LetterStatus | undefined>(undefined);
  const [selectedLetterId, setSelectedLetterId] = useState<string | null>(null);
  const [searchText, setSearchText] = useState("");
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");
  const [replyText, setReplyText] = useState("");
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const deferredSearchText = useDeferredValue(searchText.trim().toLowerCase());

  const radioStatusQuery = useQuery({
    queryKey: ["radio", "status"],
    queryFn: getRadioStatus,
    enabled: hasAdminToken,
    retry: false,
  });
  const lettersQuery = useQuery({
    queryKey: ["letters", selectedStationId, selectedStatus],
    queryFn: () => listLetters(selectedStationId ?? undefined, selectedStatus),
    enabled: hasAdminToken,
    retry: false,
  });
  const detailQuery = useQuery({
    queryKey: ["letters", "detail", selectedLetterId],
    queryFn: () => getLetter(selectedLetterId ?? ""),
    enabled: hasAdminToken && Boolean(selectedLetterId),
    retry: false,
  });
  const publicHistoryQuery = useQuery({
    queryKey: ["letters", "public-history", localLetterSubmissions.map((submission) => submission.id)],
    queryFn: () => lookupLetterPublicHistory(localLetterSubmissions.map((submission) => submission.id)),
    enabled: localLetterSubmissions.length > 0,
    retry: false,
    refetchInterval: 15_000,
    refetchOnWindowFocus: false,
  });

  const visibleLetters = (lettersQuery.data ?? []).filter((letter) => matchesLetterSearch(letter, deferredSearchText));
  const publicHistoryById = new Map((publicHistoryQuery.data?.letters ?? []).map((letter) => [letter.id, letter]));
  const mergedLocalSubmissions = localLetterSubmissions.map((submission) => mergeLocalSubmission(submission, publicHistoryById.get(submission.id)));
  const publicAdoptionEntries = mergedLocalSubmissions.filter((submission) => submission.playHistory.length > 0 || submission.adoptedInSessionId);

  useEffect(() => {
    if (!hasAdminToken) {
      setSelectedLetterId(null);
      setSelectedStatus(undefined);
      setSearchText("");
      return;
    }
    if (visibleLetters.length === 0) {
      setSelectedLetterId(null);
      return;
    }
    if (!selectedLetterId || !visibleLetters.some((letter) => letter.id === selectedLetterId)) {
      setSelectedLetterId(visibleLetters[0].id);
    }
  }, [hasAdminToken, selectedLetterId, visibleLetters]);

  const createMutation = useMutation({
    mutationFn: () =>
      createLetter(
        {
          stationId: selectedStationId,
          radioName,
          subject,
          body,
        },
        {
          idempotencyKey: createIdempotencyKey(),
        },
      ),
    onSuccess: async (response) => {
      const submission: LetterSubmissionRecord = {
        id: response.id,
        stationId: selectedStationId,
        radioName,
        subject,
        status: response.status,
        createdAt: response.createdAt,
      };
      addLocalLetterSubmission(submission);
      setSubject("");
      setBody("");
      setToastMessage("レターを送信しました");
      await queryClient.invalidateQueries({ queryKey: ["letters", "public-history"] });
      if (hasAdminToken) {
        await queryClient.invalidateQueries({ queryKey: ["letters"] });
      }
    },
  });

  useEffect(() => {
    if (!toastMessage) {
      return;
    }
    const timeout = window.setTimeout(() => setToastMessage(null), 3500);
    return () => window.clearTimeout(timeout);
  }, [toastMessage]);

  const bodyLength = body.length;
  const bodyRemaining = MAX_BODY_LENGTH - bodyLength;
  const canSubmit = Boolean(subject.trim()) && Boolean(body.trim()) && bodyLength <= MAX_BODY_LENGTH && !createMutation.isPending;

  const statusMutation = useMutation({
    mutationFn: async (status: LetterStatus) =>
      updateLetterStatus(selectedLetterId ?? "", {
        status,
        sessionId: status === "ADOPTED" ? radioStatusQuery.data?.sessionId : null,
      }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["letters"] }),
        queryClient.invalidateQueries({ queryKey: ["letters", "detail", selectedLetterId] }),
      ]);
    },
  });

  const replyMutation = useMutation({
    mutationFn: async () => replyLetter(selectedLetterId ?? "", { replyText }),
    onSuccess: async () => {
      setReplyText("");
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["letters"] }),
        queryClient.invalidateQueries({ queryKey: ["letters", "detail", selectedLetterId] }),
      ]);
    },
  });

  return (
    <PanelGrid>
      <PanelColumn className="xl:col-span-5">
        <Card>
          <SectionHeader
            eyebrow="Public"
            title="Submit a letter"
            description="公開投稿はここだけで完結します。管理 inbox は別カードに分離し、管理トークンがない場合は表示しません。"
          />
          {toastMessage ? (
            <div
              className="mb-4 rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm font-medium text-emerald-800"
              role="status"
              aria-live="polite"
              data-testid="letter-submit-toast"
            >
              {toastMessage}
            </div>
          ) : null}
          <div className="space-y-3">
            <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-600">
              現在の宛先: <span className="font-semibold text-slate-950">{selectedStationId ?? "共通宛"}</span>
            </div>
            <div>
              <label className="mb-2 block text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">radioName</label>
              <Input value={radioName} maxLength={255} onChange={(event) => setRadioName(event.target.value)} />
            </div>
            <div>
              <label className="mb-2 block text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Subject</label>
              <Input value={subject} maxLength={MAX_SUBJECT_LENGTH} onChange={(event) => setSubject(event.target.value)} placeholder="最近の作業BGM" />
            </div>
            <div>
              <div className="mb-2 flex items-center justify-between gap-2">
                <label className="block text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Body</label>
                <span className={`text-xs font-semibold ${bodyRemaining < 0 ? "text-rose-700" : bodyRemaining < 100 ? "text-amber-700" : "text-slate-500"}`}>
                  {bodyLength}/{MAX_BODY_LENGTH}
                </span>
              </div>
              <Textarea
                rows={6}
                maxLength={MAX_BODY_LENGTH}
                value={body}
                onChange={(event) => setBody(event.target.value)}
                placeholder="深夜作業でおすすめの音を教えてください。"
              />
              <p className={`mt-2 text-xs ${bodyRemaining < 0 ? "text-rose-700" : "text-slate-500"}`}>
                {bodyRemaining < 0 ? "本文は 1000 文字以内にしてください。" : `残り ${bodyRemaining} 文字`}
              </p>
            </div>
            <Button
              tone="secondary"
              disabled={!canSubmit}
              onClick={() => createMutation.mutate()}
            >
              {createMutation.isPending ? "Submitting..." : "Submit Letter"}
            </Button>
            <p className="text-sm leading-6 text-slate-600">
              投稿時は `Idempotency-Key` を付与して二重送信を抑止します。公開投稿は管理トークンなしでも利用できます。
            </p>
          </div>
        </Card>

        <Card className="mt-4">
          <SectionHeader eyebrow="Local" title="Sent from this device" description="この端末で送ったレターをローカルに保存して見返せるようにします。" />
          {mergedLocalSubmissions.length ? (
            <div className="space-y-3">
              {mergedLocalSubmissions.map((submission) => (
                <div
                  key={submission.id}
                  className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3"
                  data-testid="local-letter"
                  data-letter-id={submission.id}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <div className="font-semibold text-slate-950">{submission.subject}</div>
                    <Badge tone={submission.status === "ADOPTED" ? "accent" : submission.status === "REPLIED" ? "success" : "default"}>
                      {submission.status}
                    </Badge>
                  </div>
                  <div className="mt-1 text-sm text-slate-600">
                    {submission.radioName}
                    {submission.stationId ? ` / ${submission.stationId}` : " / 共通宛"}
                  </div>
                  {submission.adoptedInSessionId ? <div className="mt-1 text-xs text-slate-500">adopted in {submission.adoptedInSessionId}</div> : null}
                  <div className="mt-1 text-xs text-slate-500">{new Date(submission.createdAt).toLocaleString("ja-JP")}</div>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title="まだ送信履歴はありません" description="最初のレターを送ると、ここにこの端末の履歴が残ります。" />
          )}
        </Card>

        <Card className="mt-4">
          <SectionHeader
            eyebrow="Public"
            title="Broadcast adoption history"
            description="公開 API で自分が送ったレターの採用状況と放送履歴を確認します。本文や返信は含めず、最小要約だけを表示します。"
          />
          {!mergedLocalSubmissions.length ? (
            <EmptyState title="採用履歴の対象はまだありません" description="この端末からレターを送ると、採用状況をここで追跡できます。" />
          ) : publicHistoryQuery.isPending ? (
            <EmptyState title="採用履歴を確認しています" description="公開用の履歴 API から最新状態を取得しています。" />
          ) : publicHistoryQuery.error instanceof Error ? (
            <EmptyState title="採用履歴を取得できません" description={publicHistoryQuery.error.message} />
          ) : publicAdoptionEntries.length ? (
            <div className="space-y-3">
              {publicAdoptionEntries.map((submission) => (
                <div
                  key={submission.id}
                  className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-4"
                  data-testid="public-letter"
                  data-letter-id={submission.id}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <div className="font-semibold text-slate-950">{submission.subject}</div>
                    <Badge tone={submission.status === "ADOPTED" ? "accent" : submission.status === "REPLIED" ? "success" : "default"}>
                      {submission.status}
                    </Badge>
                    {submission.adoptedInSessionId ? <Badge tone="default">{submission.adoptedInSessionId}</Badge> : null}
                  </div>
                  <div className="mt-2 text-sm text-slate-600">
                    {submission.radioName}
                    {submission.stationId ? ` / ${submission.stationId}` : " / 共通宛"}
                  </div>
                  {submission.playHistory.length ? (
                    <div className="mt-3 space-y-2">
                      {submission.playHistory.map((entry) => (
                        <div key={entry.id} className="rounded-2xl bg-slate-50 px-3 py-3 text-sm text-slate-700">
                          <div className="flex flex-wrap items-center gap-2">
                            <Badge tone="accent">{entry.segmentType}</Badge>
                            <Badge tone={entry.resultStatus === "DONE" ? "success" : "default"}>{entry.resultStatus}</Badge>
                          </div>
                          <div className="mt-1 font-semibold text-slate-900">{entry.title}</div>
                          <div className="mt-1 text-xs text-slate-500">{new Date(entry.playedAt).toLocaleString("ja-JP")}</div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="mt-3 text-sm text-slate-500">採用済みですが、まだ放送履歴はありません。</p>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title="まだ採用履歴はありません" description="投稿済みレターの status が更新されると、ここに採用状況と放送履歴が表示されます。" />
          )}
        </Card>

        {!hasAdminToken ? (
          <Card className="mt-4">
            <SectionHeader
              eyebrow="Admin"
              title="Management inbox"
              description="`X-Admin-Token` か `NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN` がある時だけ管理用の一覧と操作を表示します。"
            />
            <EmptyState
              title="管理 inbox は非表示です"
              description="公開投稿は利用できます。管理用の一覧・採用・返信は管理トークン設定後に表示されます。"
            />
          </Card>
        ) : null}
      </PanelColumn>

      {hasAdminToken ? (
        <PanelColumn className="xl:col-span-7">
          <Card>
            <SectionHeader eyebrow="Inbox" title="Management inbox" description="station と status で絞り、採用・返信・放送履歴をまとめて確認します。" />
            <div className="mb-4 space-y-3">
              <div>
                <label className="mb-2 block text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Keyword</label>
                <Input value={searchText} onChange={(event) => setSearchText(event.target.value)} placeholder="件名 / radioName を検索" />
              </div>
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={() => setSelectedStatus(undefined)}
                  className={`rounded-full border px-3 py-1.5 text-xs font-semibold ${selectedStatus === undefined ? "border-slate-950 bg-slate-950 text-white" : "border-slate-200 bg-white/80 text-slate-700"}`}
                >
                  ALL
                </button>
                {letterStatuses.map((status) => (
                  <button
                    key={status}
                    type="button"
                    onClick={() => setSelectedStatus(status)}
                    className={`rounded-full border px-3 py-1.5 text-xs font-semibold ${selectedStatus === status ? "border-slate-950 bg-slate-950 text-white" : "border-slate-200 bg-white/80 text-slate-700"}`}
                  >
                    {status}
                  </button>
                ))}
              </div>
              <p className="text-xs text-slate-500">
                {lettersQuery.data?.length ?? 0} 件中 {visibleLetters.length} 件を表示
              </p>
            </div>
            {lettersQuery.data?.length ? (
              visibleLetters.length ? (
                <div className="space-y-3">
                  {visibleLetters.map((letter) => (
                    <button
                      key={letter.id}
                      type="button"
                      onClick={() => setSelectedLetterId(letter.id)}
                      data-testid="admin-letter"
                      data-letter-id={letter.id}
                      className={`w-full rounded-2xl border px-4 py-3 text-left transition ${
                        selectedLetterId === letter.id ? "border-slate-950 bg-slate-950 text-white" : "border-slate-200 bg-white/80 text-slate-800"
                      }`}
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <div className="font-semibold">{letter.subject}</div>
                        <Badge tone={letter.status === "ADOPTED" ? "accent" : letter.status === "REPLIED" ? "success" : "default"}>
                          {letter.status}
                        </Badge>
                      </div>
                      <div className="mt-1 text-sm opacity-80">{letter.radioName}</div>
                    </button>
                  ))}
                </div>
              ) : (
                <EmptyState title="検索条件に一致するレターはありません" description="status と keyword を見直すと対象が表示されます。" />
              )
            ) : (
              <EmptyState
                title="レター一覧を取得できません"
                description={lettersQuery.error instanceof Error ? lettersQuery.error.message : "管理トークンがある時に一覧が表示されます。"}
              />
            )}
          </Card>

          <Card className="mt-4">
            <SectionHeader eyebrow="Detail" title="Letter detail" description="返信履歴と放送履歴を分けずに追える管理用ビューです。" />
            {detailQuery.data ? (
              <div className="space-y-4">
                <div className="flex flex-wrap gap-2">
                  <Badge tone="accent">{detailQuery.data.status}</Badge>
                  {detailQuery.data.stationId ? <Badge tone="default">{detailQuery.data.stationId}</Badge> : null}
                  {detailQuery.data.adoptedInSessionId ? <Badge tone="default">{detailQuery.data.adoptedInSessionId}</Badge> : null}
                </div>
                <div>
                  <h3 className="text-2xl font-semibold tracking-tight text-slate-950">{detailQuery.data.subject}</h3>
                  <p className="mt-2 text-sm leading-7 text-slate-700">{detailQuery.data.body}</p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button tone="ghost" disabled={!selectedLetterId} onClick={() => statusMutation.mutate("PENDING")}>
                    Mark Pending
                  </Button>
                  <Button
                    tone="secondary"
                    disabled={!selectedLetterId || !radioStatusQuery.data?.sessionId}
                    onClick={() => statusMutation.mutate("ADOPTED")}
                  >
                    Adopt To Current Session
                  </Button>
                  <Button tone="primary" disabled={!selectedLetterId || detailQuery.data.status === "UNREAD"} onClick={() => statusMutation.mutate("REPLIED")}>
                    Mark Replied
                  </Button>
                </div>
                <div className="grid gap-4 lg:grid-cols-2">
                  <div className="rounded-3xl border border-slate-200 bg-slate-50 px-4 py-4">
                    <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Replies</div>
                    <div className="mt-3 space-y-3">
                      {detailQuery.data.replies.length ? (
                        detailQuery.data.replies.map((reply) => (
                          <div key={reply.id} className="rounded-2xl bg-white px-3 py-3 text-sm text-slate-700">
                            {reply.replyText}
                          </div>
                        ))
                      ) : (
                        <EmptyState title="返信はまだありません" />
                      )}
                    </div>
                  </div>
                  <div className="rounded-3xl border border-slate-200 bg-slate-50 px-4 py-4">
                    <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Play history</div>
                    <div className="mt-3 space-y-3">
                      {detailQuery.data.playHistory.length ? (
                        detailQuery.data.playHistory.map((entry) => (
                          <div key={entry.id} className="rounded-2xl bg-white px-3 py-3 text-sm text-slate-700">
                            <div className="font-semibold text-slate-900">{entry.title}</div>
                            <div className="mt-1">
                              {entry.segmentType} / {entry.resultStatus}
                            </div>
                          </div>
                        ))
                      ) : (
                        <EmptyState title="放送履歴はまだありません" />
                      )}
                    </div>
                  </div>
                </div>
                <div>
                  <label className="mb-2 block text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Reply draft</label>
                  <Textarea rows={5} value={replyText} onChange={(event) => setReplyText(event.target.value)} placeholder="ありがとうございます。" />
                  <div className="mt-3">
                    <Button
                      tone="secondary"
                      disabled={!replyText.trim() || replyMutation.isPending || !["PENDING", "ADOPTED"].includes(detailQuery.data.status)}
                      onClick={() => replyMutation.mutate()}
                    >
                      Add Reply
                    </Button>
                  </div>
                </div>
              </div>
            ) : (
              <EmptyState
                title="レターを選択してください"
                description={detailQuery.error instanceof Error ? detailQuery.error.message : "左側の一覧から 1 件選ぶと詳細を表示します。"}
              />
            )}
          </Card>
        </PanelColumn>
      ) : (
        <PanelColumn className="xl:col-span-7">
          <Card>
            <SectionHeader eyebrow="Public" title="What happens after submit?" description="管理トークンがない場合は、公開投稿の送信だけを案内します。" />
            <EmptyState
              title="公開投稿専用モード"
              description="この画面では投稿のみ可能です。採用状況、返信、放送履歴の確認は管理トークン設定後に表示されます。"
            />
          </Card>
        </PanelColumn>
      )}
    </PanelGrid>
  );
}

function createIdempotencyKey() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `letter-${Math.random().toString(36).slice(2, 12)}`;
}

function matchesLetterSearch(
  letter: { subject: string; radioName: string; stationId: string | null },
  searchText: string,
) {
  if (!searchText) {
    return true;
  }
  return [letter.subject, letter.radioName, letter.stationId ?? ""].some((value) => value.toLowerCase().includes(searchText));
}

function mergeLocalSubmission(submission: LetterSubmissionRecord, publicLetter?: LetterPublicSummary) {
  return {
    ...submission,
    status: publicLetter?.status ?? submission.status,
    adoptedInSessionId: publicLetter?.adoptedInSessionId ?? null,
    playHistory: publicLetter?.playHistory ?? [],
  };
}
