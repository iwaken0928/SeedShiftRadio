"use client";

import { useEffect, useRef, useState } from "react";
import { Button, Card, Badge } from "@/components/ui";
import type { PlaybackEventRequest } from "@/lib/types";

type Props = {
  sourceUrl: string | null;
  label: string;
  itemId: string | null;
  sessionId: string | null;
  volume: number;
  onPlaybackEvent: (request: PlaybackEventRequest) => Promise<void> | void;
};

export function AudioConsole({ sourceUrl, label, itemId, sessionId, volume, onPlaybackEvent }: Props) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [playing, setPlaying] = useState(false);
  const sentStartRef = useRef<string | null>(null);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) {
      return;
    }
    audio.volume = volume;
  }, [volume]);

  useEffect(() => {
    sentStartRef.current = null;
  }, [sourceUrl, itemId]);

  const play = async () => {
    const audio = audioRef.current;
    if (!audio || !sourceUrl) {
      return;
    }
    if (audio.src !== sourceUrl) {
      audio.src = sourceUrl;
    }
    try {
      await audio.play();
      setPlaying(true);
      if (itemId && sentStartRef.current !== itemId) {
        sentStartRef.current = itemId;
        await onPlaybackEvent({
          sessionId: sessionId ?? "",
          itemId,
          eventType: "SEGMENT_STARTED",
        });
      }
    } catch {
      if (itemId) {
        await onPlaybackEvent({
          sessionId: sessionId ?? "",
          itemId,
          eventType: "SEGMENT_ERROR",
        });
      }
    }
  };

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
    audio.pause();
    audio.currentTime = 0;
    setPlaying(false);
    if (itemId) {
      await onPlaybackEvent({
        sessionId: sessionId ?? "",
        itemId,
        eventType: "PLAYBACK_STOPPED",
      });
    }
  };

  return (
    <Card tone="dark" className="relative overflow-hidden">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_top_right,rgba(20,184,166,0.22),transparent_28%),radial-gradient(circle_at_bottom_left,rgba(194,101,54,0.18),transparent_24%)]" />
      <div className="relative space-y-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div className="text-xs font-semibold uppercase tracking-[0.22em] text-teal-200">Audio console</div>
            <div className="mt-1 text-lg font-semibold text-white">{label}</div>
          </div>
          <Badge tone={playing ? "success" : "default"}>{playing ? "PLAYING" : "READY"}</Badge>
        </div>

        <div className="flex flex-wrap gap-2">
          <Button type="button" tone="secondary" onClick={() => void play()} disabled={!sourceUrl}>
            Play
          </Button>
          <Button type="button" tone="ghost" onClick={pause}>
            Pause
          </Button>
          <Button type="button" tone="danger" onClick={() => void stop()}>
            Stop
          </Button>
        </div>

        <audio
          ref={audioRef}
          className="hidden"
          preload="auto"
          onEnded={() => {
            setPlaying(false);
            if (itemId) {
              void onPlaybackEvent({
                sessionId: sessionId ?? "",
                itemId,
                eventType: "SEGMENT_ENDED",
              });
            }
          }}
          onError={() => {
            setPlaying(false);
            if (itemId) {
              void onPlaybackEvent({
                sessionId: sessionId ?? "",
                itemId,
                eventType: "SEGMENT_ERROR",
              });
            }
          }}
        />

        <p className="text-sm leading-6 text-slate-200">
          {sourceUrl ? "現在の READY セグメントを再生できます。" : "再生可能な asset がまだありません。"}
        </p>
      </div>
    </Card>
  );
}
