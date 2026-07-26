import { expect, test } from "@playwright/test";
import {
  apiRegExp,
  apiUrl,
  appUrl,
  buildProgramBlock,
  buildQueueItem,
  buildQueueSnapshot,
  buildRadioStatus,
  buildSpeechDirective,
  buildStation,
  clearPersistedUiState,
  fulfillEmpty,
  fulfillJson,
  mockUnavailableStream,
  stubAudioPlayback,
  UI_STORE_STORAGE_KEY,
} from "./fixtures";

test("radio: Tune -> Play -> audio playback event POST", async ({ page }) => {
  await clearPersistedUiState(page);
  await stubAudioPlayback(page);

  const station = buildStation();
  const readyItem = buildQueueItem();
  const program = buildProgramBlock();
  let status = buildRadioStatus();
  let queue = buildQueueSnapshot({ items: [] });
  const playbackEvents: Array<Record<string, unknown>> = [];
  const tuneRequests: Array<Record<string, unknown>> = [];
  let releasePlayResponse!: () => void;
  const playResponseGate = new Promise<void>((resolve) => {
    releasePlayResponse = resolve;
  });

  await mockUnavailableStream(page);

  await page.route(apiUrl("/api/stations"), async (route) => {
    await fulfillJson(route, [station]);
  });

  await page.route(apiUrl("/api/radio/status"), async (route) => {
    await fulfillJson(route, status);
  });

  await page.route(apiUrl("/api/radio/queue"), async (route) => {
    await fulfillJson(route, queue);
  });

  await page.route(apiUrl("/api/radio/program"), async (route) => {
    await fulfillJson(route, program);
  });

  await page.route(apiRegExp("/api/radio/next-speech-directive\\?clientId=.*"), async (route) => {
    await fulfillJson(route, buildSpeechDirective());
  });

  await page.route(apiUrl("/api/clients/capabilities"), async (route) => {
    await fulfillEmpty(route);
  });

  await page.route(apiUrl("/api/radio/tune"), async (route) => {
    const request = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
    tuneRequests.push(request);

    status = buildRadioStatus({
      sessionId: "session-night-001",
      stationId: station.id,
      programBlockId: program.id,
      programTemplateId: program.templateId,
      programTitle: program.title,
      state: "PREPARING",
      currentItemId: readyItem.id,
      bufferReadyCount: 1,
      correlationId: "corr-night-001",
    });
    queue = buildQueueSnapshot({
      sessionId: "session-night-001",
      stationId: station.id,
      items: [readyItem],
      correlationId: "corr-night-001",
    });

    await fulfillJson(route, {
      sessionId: "session-night-001",
      state: "PREPARING",
      correlationId: "corr-night-001",
    });
  });

  await page.route(apiUrl("/api/radio/play"), async (route) => {
    await playResponseGate;
    status = {
      ...status,
      state: "PLAYING",
      updatedAt: "2026-04-22T00:00:02Z",
    };
    await fulfillJson(route, status);
  });

  await page.route(apiUrl("/api/radio/playback-events"), async (route) => {
    const request = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
    playbackEvents.push(request);
    await fulfillEmpty(route);
  });

  await page.route(apiRegExp("/api/assets/audio/.*"), async (route) => {
    await route.fulfill({
      status: 200,
      body: Buffer.from("UklGRiYAAABXQVZFZm10IBAAAAABAAEAQB8AAIA+AAACABAAZGF0YQIAAAAAAA==", "base64"),
      contentType: "audio/wav",
    });
  });

  await page.goto(appUrl("/"));

  await expect(page.getByRole("button", { name: station.name, exact: true })).toBeVisible();
  await expect(page.getByTestId("radio-tune")).toBeEnabled();

  await page.getByTestId("radio-tune").click();

  await expect.poll(() => tuneRequests.length).toBe(1);
  await expect.poll(() => tuneRequests[0]?.stationId).toBe(station.id);
  await expect(page.getByTestId("radio-state")).toContainText("PREPARING");
  await expect(page.getByTestId("radio-now-playing-title")).toHaveText(readyItem.title);
  await expect(page.locator('[data-testid="queue-item"][data-itemid="queue-001"]')).toContainText(readyItem.title);

  await page.getByTestId("radio-play").click();

  await expect.poll(() => page.evaluate(() => (window as Window & { __seedshiftAudioPlayCount?: number }).__seedshiftAudioPlayCount ?? 0)).toBe(1);
  releasePlayResponse();
  await expect(page.getByTestId("radio-state")).toContainText("PLAYING");
  await expect.poll(() => playbackEvents.length).toBe(1);
  await expect(playbackEvents[0]).toMatchObject({
    sessionId: "session-night-001",
    itemId: readyItem.id,
    eventType: "SEGMENT_STARTED",
  });
});

test("radio: 1回の再生操作で同じ番組を最後まで順次再生する", async ({ page }) => {
  await clearPersistedUiState(page);
  await stubAudioPlayback(page);

  const station = buildStation();
  const program = buildProgramBlock();
  const opening = buildQueueItem();
  const music = buildQueueItem({
    id: "queue-002",
    programSlotId: "slot-music-001",
    slotRole: "MUSIC_BREAK",
    type: "MUSIC_AI",
    title: "Night Drive Music",
    assetUrl: "/api/assets/audio/asset-002.wav",
  });
  const nextProgramOpening = buildQueueItem({
    id: "queue-003",
    programBlockId: "block-night-002",
    programSlotId: "slot-opening-002",
    title: "Next Program Intro",
    assetUrl: "/api/assets/audio/asset-003.wav",
  });
  let items = [opening, music, nextProgramOpening];
  let status = buildRadioStatus({
    sessionId: "session-night-001",
    stationId: station.id,
    programBlockId: program.id,
    programTemplateId: program.templateId,
    programTitle: program.title,
    state: "PREPARING",
    currentItemId: null,
    bufferReadyCount: 3,
    correlationId: "corr-night-001",
  }) as Record<string, unknown> & {
    state: string;
    currentItemId: string | null;
    programBlockId: string | null;
  };
  const playbackEvents: Array<Record<string, unknown>> = [];
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));

  await mockUnavailableStream(page);
  await page.route(apiUrl("/api/stations"), (route) => fulfillJson(route, [station]));
  await page.route(apiUrl("/api/radio/status"), (route) => fulfillJson(route, status));
  await page.route(apiUrl("/api/radio/queue"), (route) => fulfillJson(route, buildQueueSnapshot({ items })));
  await page.route(apiUrl("/api/radio/program"), (route) => fulfillJson(route, program));
  await page.route(apiRegExp("/api/radio/next-speech-directive\\?clientId=.*"), (route) => fulfillJson(route, buildSpeechDirective()));
  await page.route(apiUrl("/api/clients/capabilities"), (route) => fulfillEmpty(route));
  await page.route(apiUrl("/api/radio/play"), async (route) => {
    status = { ...status, state: "PLAYING", currentItemId: opening.id };
    items = items.map((item) => item.id === opening.id ? { ...item, status: "PLAYING" } : item);
    await fulfillJson(route, status);
  });
  await page.route(apiUrl("/api/radio/playback-events"), async (route) => {
    const request = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
    playbackEvents.push(request);
    if (request.eventType === "SEGMENT_STARTED") {
      status = {
        ...status,
        state: "PLAYING",
        currentItemId: typeof request.itemId === "string" ? request.itemId : null,
      };
      items = items.map((item) => item.id === request.itemId ? { ...item, status: "PLAYING" } : item);
    }
    if (request.eventType === "SEGMENT_ENDED") {
      status = {
        ...status,
        state: "PREPARING",
        currentItemId: null,
        programBlockId: request.itemId === music.id ? nextProgramOpening.programBlockId : status.programBlockId,
      };
      items = items.map((item) => item.id === request.itemId ? { ...item, status: "DONE" } : item);
    }
    await fulfillEmpty(route);
  });
  await page.route(apiRegExp("/api/assets/audio/.*"), async (route) => {
    await route.fulfill({
      status: 200,
      body: Buffer.from("UklGRiYAAABXQVZFZm10IBAAAAABAAEAQB8AAIA+AAACABAAZGF0YQIAAAAAAA==", "base64"),
      contentType: "audio/wav",
    });
  });

  await page.goto(appUrl("/"));
  await page.getByTestId("radio-play").click();

  await expect.poll(() => playbackEvents.map((event) => `${event.itemId}:${event.eventType}`)).toContain(
    `${opening.id}:SEGMENT_STARTED`,
  );
  await page.getByTestId("audio-element").dispatchEvent("ended");

  await expect.poll(() => page.evaluate(() => (window as Window & { __seedshiftAudioPlayCount?: number }).__seedshiftAudioPlayCount ?? 0)).toBe(2);
  await expect.poll(() => playbackEvents.map((event) => `${event.itemId}:${event.eventType}`)).toEqual([
    `${opening.id}:SEGMENT_STARTED`,
    `${opening.id}:SEGMENT_ENDED`,
    `${music.id}:SEGMENT_STARTED`,
  ]);
  await expect(page.getByTestId("radio-now-playing-title")).toHaveText(music.title);

  await page.getByTestId("audio-element").dispatchEvent("ended");

  await expect(page.getByTestId("radio-now-playing-title")).toHaveText(nextProgramOpening.title);
  await expect.poll(() => page.evaluate(() => (window as Window & { __seedshiftAudioPlayCount?: number }).__seedshiftAudioPlayCount ?? 0)).toBe(2);
  await expect.poll(() => pageErrors).toEqual([]);
});

test("radio: browser の再生拒否を画面へ表示する", async ({ page }) => {
  await clearPersistedUiState(page);
  await page.addInitScript(() => {
    Object.defineProperty(HTMLMediaElement.prototype, "play", {
      configurable: true,
      value() {
        return Promise.reject(new DOMException("playback blocked", "NotAllowedError"));
      },
    });
    Object.defineProperty(HTMLMediaElement.prototype, "pause", { configurable: true, value() {} });
    Object.defineProperty(HTMLMediaElement.prototype, "load", { configurable: true, value() {} });
  });

  const station = buildStation();
  const readyItem = buildQueueItem();
  const status = buildRadioStatus({
    sessionId: "session-night-001",
    stationId: station.id,
    programBlockId: "block-night-001",
    currentItemId: readyItem.id,
    bufferReadyCount: 1,
  });

  await mockUnavailableStream(page);
  await page.route(apiUrl("/api/stations"), (route) => fulfillJson(route, [station]));
  await page.route(apiUrl("/api/radio/status"), (route) => fulfillJson(route, status));
  await page.route(apiUrl("/api/radio/queue"), (route) => fulfillJson(route, buildQueueSnapshot({ items: [readyItem] })));
  await page.route(apiUrl("/api/radio/program"), (route) => fulfillJson(route, buildProgramBlock()));
  await page.route(apiRegExp("/api/radio/next-speech-directive\\?clientId=.*"), (route) => fulfillJson(route, buildSpeechDirective()));
  await page.route(apiUrl("/api/clients/capabilities"), (route) => fulfillEmpty(route));
  await page.route(apiUrl("/api/radio/play"), (route) => fulfillJson(route, status));
  await page.route(apiUrl("/api/radio/stop"), (route) => fulfillJson(route, { ...status, state: "STOPPED" }));

  await page.goto(appUrl("/"));
  await page.getByTestId("radio-play").click();

  await expect(page.getByTestId("audio-console").getByRole("alert")).toContainText(
    "ブラウザーが音声再生を許可しませんでした",
  );
});

test("radio: 保存済みUI状態があっても hydration error を起こさない", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await page.addInitScript(({ storageKey }) => {
    window.localStorage.setItem(storageKey, JSON.stringify({
      state: {
        clientId: "web-persisted",
        selectedStationId: "station-night",
        radioName: "Persisted Listener",
        volume: 0.35,
        activeRoute: "radio",
        localLetterSubmissions: [],
      },
      version: 0,
    }));
  }, { storageKey: UI_STORE_STORAGE_KEY });

  await mockUnavailableStream(page);
  await page.route(apiUrl("/api/stations"), (route) => fulfillJson(route, [buildStation()]));
  await page.route(apiUrl("/api/radio/status"), (route) => fulfillJson(route, buildRadioStatus()));
  await page.route(apiUrl("/api/clients/capabilities"), (route) => fulfillEmpty(route));

  await page.goto(appUrl("/"));

  await expect(page.getByText("web-persisted", { exact: true })).toBeVisible();
  await expect.poll(() => pageErrors.filter((message) => message.includes("418") || message.includes("Hydration")).length).toBe(0);
});
