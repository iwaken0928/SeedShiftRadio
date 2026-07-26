"use client";

import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import { Button, Card, Badge } from "@/components/ui";
import type { PlaybackEventRequest } from "@/lib/types";

type Props = {
  sourceUrl: string | null;
  label: string;
  clientId: string;
  itemId: string | null;
  sessionId: string | null;
  volume: number;
  onPlaybackEvent: (request: PlaybackEventRequest) => Promise<void> | void;
};

export type AudioConsoleHandle = {
  play: () => Promise<void>;
  reset: () => void;
};

export const AudioConsole = forwardRef<AudioConsoleHandle, Props>(function AudioConsole(
  { sourceUrl, label, clientId, itemId, sessionId, volume, onPlaybackEvent },
  ref,
) {
    const audioRef = useRef<HTMLAudioElement | null>(null);
    const [playing, setPlaying] = useState(false);
    const [playbackError, setPlaybackError] = useState<string | null>(null);
    const sentStartRef = useRef<string | null>(null);
    const playbackTargetRef = useRef({ clientId, itemId, sessionId, onPlaybackEvent });
    playbackTargetRef.current = { clientId, itemId, sessionId, onPlaybackEvent };

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) {
      return;
    }
    audio.volume = volume;
  }, [volume]);

  useEffect(() => {
    const audio = audioRef.current;
    if (audio) {
      audio.pause();
      audio.currentTime = 0;
      audio.load();
    }
    sentStartRef.current = null;
    setPlaying(false);
    setPlaybackError(null);
  }, [sourceUrl, itemId]);

  useEffect(() => {
    const audio = audioRef.current;
    return () => {
      if (!audio || audio.paused) {
        return;
      }
      audio.pause();
      const target = playbackTargetRef.current;
      if (!target.sessionId || !target.itemId) {
        return;
      }
      void target.onPlaybackEvent({
        clientId: target.clientId,
        sessionId: target.sessionId,
        itemId: target.itemId,
        eventType: "PLAYBACK_STOPPED",
        occurredAt: new Date().toISOString(),
      });
    };
  }, []);

  const emitPlaybackEvent = async (eventType: PlaybackEventRequest["eventType"]) => {
    if (!sessionId || !itemId) {
      return;
    }
    await onPlaybackEvent({
      clientId,
      sessionId,
      itemId,
      eventType,
      occurredAt: new Date().toISOString(),
    });
  };

  const play = async () => {
    const audio = audioRef.current;
    if (!audio || !sourceUrl) {
      throw new Error("再生可能な音声アセットがありません。");
    }
    setPlaybackError(null);
    try {
      await audio.play();
      setPlaying(true);
      if (itemId && sentStartRef.current !== itemId) {
        sentStartRef.current = itemId;
        void emitPlaybackEvent("SEGMENT_STARTED").catch(() => {
          setPlaybackError("音声は再生中ですが、Server へ再生開始を通知できませんでした。");
        });
      }
    } catch (cause) {
      const message = playbackFailureMessage(cause);
      setPlaying(false);
      setPlaybackError(message);
      throw new Error(message);
    }
  };

  const reset = () => {
    const audio = audioRef.current;
    if (!audio) {
      return;
    }
    audio.pause();
    audio.currentTime = 0;
    setPlaying(false);
  };

  useImperativeHandle(ref, () => ({ play, reset }));

  const pause = () => {
    const audio = audioRef.current;
    if (!audio) {
      return;
    }
    audio.pause();
    setPlaying(false);
  };

  const stop = async () => {
    const audio = audioRef.current;
    if (!audio) {
      return;
    }
    reset();
    await emitPlaybackEvent("PLAYBACK_STOPPED");
  };

  return (
    <Card tone="dark" className="relative overflow-hidden" data-testid="audio-console">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_top_right,rgba(20,184,166,0.22),transparent_28%),radial-gradient(circle_at_bottom_left,rgba(194,101,54,0.18),transparent_24%)]" />
      <div className="relative space-y-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div className="text-xs font-semibold uppercase tracking-[0.22em] text-teal-200">音声プレイヤー</div>
            <div className="mt-1 text-lg font-semibold text-white">{label}</div>
          </div>
          <Badge tone={playing ? "success" : "default"}>{playing ? "再生中" : "再生可能"}</Badge>
        </div>

        <div className="flex flex-wrap gap-2">
          <Button type="button" tone="secondary" onClick={() => void play().catch(() => undefined)} disabled={!sourceUrl} data-testid="audio-play">
            再生
          </Button>
          <Button type="button" tone="ghost" onClick={pause} data-testid="audio-pause">
            一時停止
          </Button>
          <Button type="button" tone="danger" onClick={() => void stop()} data-testid="audio-stop">
            停止
          </Button>
        </div>

        <audio
          ref={audioRef}
          src={sourceUrl ?? undefined}
          className="hidden"
          preload="auto"
          data-testid="audio-element"
          onEnded={() => {
            setPlaying(false);
            void emitPlaybackEvent("SEGMENT_ENDED");
          }}
          onError={() => {
            setPlaying(false);
            setPlaybackError("音声アセットを読み込めませんでした。しばらく待ってから再試行してください。");
            void emitPlaybackEvent("SEGMENT_ERROR");
          }}
        />

        {playbackError ? <p className="text-sm font-semibold text-rose-200" role="alert">{playbackError}</p> : null}
        <p className="text-sm leading-6 text-slate-200">
          {sourceUrl ? "現在の READY セグメントを再生できます。" : "再生可能な asset がまだありません。"}
        </p>
      </div>
    </Card>
  );
});

function playbackFailureMessage(cause: unknown) {
  if (cause instanceof DOMException && cause.name === "NotAllowedError") {
    return "ブラウザーが音声再生を許可しませんでした。画面を操作してから、もう一度再生してください。";
  }
  if (cause instanceof DOMException && cause.name === "NotSupportedError") {
    return "この音声形式を再生できませんでした。別のセグメントをお試しください。";
  }
  return cause instanceof Error && cause.message
    ? `音声を再生できませんでした: ${cause.message}`
    : "音声を再生できませんでした。もう一度お試しください。";
}
