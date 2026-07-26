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

  await expect(page.getByTestId("radio-state")).toContainText("PLAYING");
  await expect.poll(() => playbackEvents.length).toBe(1);
  await expect(playbackEvents[0]).toMatchObject({
    sessionId: "session-night-001",
    itemId: readyItem.id,
    eventType: "SEGMENT_STARTED",
  });
});
