"use client";

import { useEffect, useRef, useState } from "react";
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

export function AudioConsole({ sourceUrl, label, clientId, itemId, sessionId, volume, onPlaybackEvent }: Props) {
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
    const audio = audioRef.current;
    if (audio) {
      audio.pause();
      audio.currentTime = 0;
      audio.removeAttribute("src");
      audio.load();
    }
    sentStartRef.current = null;
    setPlaying(false);
  }, [sourceUrl, itemId]);

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
        await emitPlaybackEvent("SEGMENT_STARTED");
      }
    } catch {
      await emitPlaybackEvent("SEGMENT_ERROR");
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
    await emitPlaybackEvent("PLAYBACK_STOPPED");
  };

  return (
    <Card tone="dark" className="relative overflow-hidden" data-testid="audio-console">
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
          <Button type="button" tone="secondary" onClick={() => void play()} disabled={!sourceUrl} data-testid="audio-play">
            Play
          </Button>
          <Button type="button" tone="ghost" onClick={pause} data-testid="audio-pause">
            Pause
          </Button>
          <Button type="button" tone="danger" onClick={() => void stop()} data-testid="audio-stop">
            Stop
          </Button>
        </div>

        <audio
          ref={audioRef}
          className="hidden"
          preload="auto"
          data-testid="audio-element"
          onEnded={() => {
            setPlaying(false);
            void emitPlaybackEvent("SEGMENT_ENDED");
          }}
          onError={() => {
            setPlaying(false);
            void emitPlaybackEvent("SEGMENT_ERROR");
          }}
        />

        <p className="text-sm leading-6 text-slate-200">
          {sourceUrl ? "現在の READY セグメントを再生できます。" : "再生可能な asset がまだありません。"}
        </p>
      </div>
    </Card>
  );
}
