"use client";

import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createLetter, getLetter, getRadioStatus, listLetters, replyLetter, updateLetterStatus } from "@/lib/api";
import { getAdminToken } from "@/lib/env";
import { PanelColumn, PanelGrid } from "@/components/markdown";
import { Badge, Button, Card, EmptyState, Input, SectionHeader, Textarea } from "@/components/ui";
import { useUiStore } from "@/stores/ui-store";
import type { LetterStatus } from "@/lib/types";

const letterStatuses: LetterStatus[] = ["UNREAD", "PENDING", "ADOPTED", "REPLIED"];

export function LettersDashboard() {
  const queryClient = useQueryClient();
  const hasAdminToken = Boolean(getAdminToken());
  const selectedStationId = useUiStore((state) => state.selectedStationId);
  const radioName = useUiStore((state) => state.radioName);
  const [selectedStatus, setSelectedStatus] = useState<LetterStatus | undefined>(undefined);
  const [selectedLetterId, setSelectedLetterId] = useState<string | null>(null);
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");
  const [replyText, setReplyText] = useState("");

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

  useEffect(() => {
    if (!hasAdminToken) {
      setSelectedLetterId(null);
      setSelectedStatus(undefined);
      return;
    }
    if (!lettersQuery.data || lettersQuery.data.length === 0) {
      setSelectedLetterId(null);
      return;
    }
    if (!selectedLetterId || !lettersQuery.data.some((letter) => letter.id === selectedLetterId)) {
      setSelectedLetterId(lettersQuery.data[0].id);
    }
  }, [hasAdminToken, lettersQuery.data, selectedLetterId]);

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
    onSuccess: async () => {
      setSubject("");
      setBody("");
      if (hasAdminToken) {
        await queryClient.invalidateQueries({ queryKey: ["letters"] });
      }
    },
  });

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
          <div className="space-y-3">
            <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-600">
              現在の宛先: <span className="font-semibold text-slate-950">{selectedStationId ?? "共通宛"}</span>
            </div>
            <div>
              <label className="mb-2 block text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">radioName</label>
              <Input value={radioName} readOnly />
            </div>
            <div>
              <label className="mb-2 block text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Subject</label>
              <Input value={subject} onChange={(event) => setSubject(event.target.value)} placeholder="最近の作業BGM" />
            </div>
            <div>
              <label className="mb-2 block text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Body</label>
              <Textarea
                rows={6}
                value={body}
                onChange={(event) => setBody(event.target.value)}
                placeholder="深夜作業でおすすめの音を教えてください。"
              />
            </div>
            <Button
              tone="secondary"
              disabled={!subject.trim() || !body.trim() || createMutation.isPending}
              onClick={() => createMutation.mutate()}
            >
              {createMutation.isPending ? "Submitting..." : "Submit Letter"}
            </Button>
            <p className="text-sm leading-6 text-slate-600">
              投稿時は `Idempotency-Key` を付与して二重送信を抑止します。公開投稿は管理トークンなしでも利用できます。
            </p>
          </div>
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
            <div className="mb-4 flex flex-wrap gap-2">
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
            {lettersQuery.data?.length ? (
              <div className="space-y-3">
                {lettersQuery.data.map((letter) => (
                  <button
                    key={letter.id}
                    type="button"
                    onClick={() => setSelectedLetterId(letter.id)}
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
                  <Button tone="primary" disabled={!selectedLetterId} onClick={() => statusMutation.mutate("REPLIED")}>
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
                    <Button tone="secondary" disabled={!replyText.trim() || replyMutation.isPending} onClick={() => replyMutation.mutate()}>
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
