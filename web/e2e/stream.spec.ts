import { expect, test, type Route } from "@playwright/test";
import {
  apiUrl,
  appUrl,
  clearPersistedUiState,
  fulfillJson,
  sseEvent,
} from "./fixtures";

test("sse: subtitle update and Last-Event-ID resend on reconnect", async ({ page }) => {
  await clearPersistedUiState(page);

  const firstSubtitle = "First subtitle from stream";
  const secondSubtitle = "Subtitle after reconnect";
  let streamRequestCount = 0;

  await page.route(apiUrl("/api/stream/events"), async (route) => {
    streamRequestCount += 1;
    const lastEventId = (await route.request().allHeaders())["last-event-id"] ?? null;

    if (streamRequestCount === 1) {
      await fulfillClosingSse(
        route,
        sseEvent({
          id: "subtitle-101",
          event: "subtitle.updated",
          data: { text: firstSubtitle },
        }),
      );
      return;
    }

    if (streamRequestCount === 2 && lastEventId === "subtitle-101") {
      await fulfillClosingSse(
        route,
        sseEvent({
          id: "subtitle-102",
          event: "subtitle.updated",
          data: { text: secondSubtitle },
        }),
      );
      return;
    }

    await fulfillJson(route, { message: "stream closed" }, 503);
  });

  await page.goto(appUrl("/stream-harness"));

  await expect(page.getByTestId("stream-harness-subtitle")).toHaveText(firstSubtitle);
  await expect(page.getByTestId("stream-harness-last-event-id")).toHaveText("subtitle-101");
  await page.getByTestId("stream-harness-reconnect").click();
  await expect(page.getByTestId("stream-harness-subtitle")).toHaveText(secondSubtitle);
  await expect(page.getByTestId("stream-harness-last-event-id")).toHaveText("subtitle-102");
});

async function fulfillClosingSse(route: Route, body: string) {
  await route.fulfill({
    status: 200,
    body,
    headers: {
      "Cache-Control": "no-cache",
      Connection: "close",
      "Content-Type": "text/event-stream; charset=utf-8",
    },
  });
}
