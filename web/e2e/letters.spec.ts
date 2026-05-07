import { expect, test } from "@playwright/test";
import {
  apiUrl,
  appUrl,
  buildPublicLetter,
  buildRadioStatus,
  clearPersistedUiState,
  fillInput,
  fulfillJson,
  inputFollowingLabel,
  mockUnavailableStream,
  panelByHeading,
  textareaFollowingLabel,
} from "./fixtures";

test("letters: submit -> local history -> public adoption history", async ({ page }) => {
  await clearPersistedUiState(page);

  const createdAt = "2026-04-22T00:10:00Z";
  const createdLetterId = "letter-001";
  const createRequests: Array<{ body: Record<string, unknown>; headers: Record<string, string> }> = [];
  const publicHistoryRequests: Array<Record<string, unknown>> = [];

  await mockUnavailableStream(page);

  await page.route(apiUrl("/api/radio/status"), async (route) => {
    await fulfillJson(route, buildRadioStatus({
      sessionId: "session-night-001",
      stationId: "station-night",
      state: "PLAYING",
    }));
  });

  await page.route(apiUrl("/api/letters"), async (route) => {
    if (route.request().method() === "GET") {
      await fulfillJson(route, []);
      return;
    }

    createRequests.push({
      body: JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>,
      headers: route.request().headers(),
    });

    await fulfillJson(route, {
      id: createdLetterId,
      status: "UNREAD",
      createdAt,
    });
  });

  await page.route(apiUrl("/api/letters/public/history"), async (route) => {
    const request = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
    publicHistoryRequests.push(request);

    await fulfillJson(route, {
      letters: [
        buildPublicLetter({
          id: createdLetterId,
          createdAt,
          radioName: "Listener Zero",
          subject: "Need a night playlist",
        }),
      ],
    });
  });

  await page.goto(appUrl("/letters"));

  await fillInput(inputFollowingLabel(page, "radioName"), "Listener Zero");
  await fillInput(inputFollowingLabel(page, "Subject"), "Need a night playlist");
  await fillInput(textareaFollowingLabel(page, "Body"), "Please share a calm coding playlist for late-night work.");

  await page.getByRole("button", { name: "Submit Letter", exact: true }).click();

  await expect.poll(() => createRequests.length).toBe(1);
  await expect(createRequests[0]?.headers["idempotency-key"]).toBeTruthy();
  await expect(createRequests[0]?.body).toMatchObject({
    stationId: null,
    radioName: "Listener Zero",
    subject: "Need a night playlist",
    body: "Please share a calm coding playlist for late-night work.",
  });

  await expect(page.getByTestId("letter-submit-toast")).toContainText("レターを送信しました");

  const localHistory = panelByHeading(page, "Sent from this device");
  await expect(localHistory.locator('[data-testid="local-letter"][data-letter-id="letter-001"]')).toContainText("Need a night playlist");
  await expect(localHistory.locator('[data-testid="local-letter"][data-letter-id="letter-001"]')).toContainText("Listener Zero / 共通宛");

  await expect.poll(() => publicHistoryRequests.length).toBe(1);
  await expect(publicHistoryRequests[0]).toEqual({
    letterIds: [createdLetterId],
  });

  const publicHistory = panelByHeading(page, "Broadcast adoption history");
  await expect(publicHistory.locator('[data-testid="public-letter"][data-letter-id="letter-001"]')).toContainText("ADOPTED");
  await expect(publicHistory.locator('[data-testid="public-letter"][data-letter-id="letter-001"]')).toContainText("session-night-001");
  await expect(publicHistory.locator('[data-testid="public-letter"][data-letter-id="letter-001"]')).toContainText("Listener Mail Spotlight");
});
