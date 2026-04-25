import { expect, test, type Page } from "@playwright/test";
import {
  apiRegExp,
  apiUrl,
  appUrl,
  buildProgramTemplateDetail,
  buildProgramTemplateSummary,
  buildSettingsResponse,
  buildStation,
  buildStationDetail,
  buildStationProgrammingResponse,
  clearPersistedUiState,
  fulfillJson,
  mockUnavailableStream,
  panelByHeading,
} from "./fixtures";

type RequestCapture = {
  body: Record<string, unknown>;
  headers: Record<string, string>;
};

type TemplateSummaryState = ReturnType<typeof buildProgramTemplateSummary>;

test.beforeEach(async ({ page }) => {
  await clearPersistedUiState(page);
  await mockUnavailableStream(page);
});

test("settings: existing station programming policy save", async ({ page }) => {
  const state = createSettingsState();
  const programmingUpdateRequests: RequestCapture[] = [];

  await installSettingsRoutes(page, state, { programmingUpdateRequests });
  await page.goto(appUrl("/settings"));

  const stationPanel = panelByHeading(page, "Station overview");

  await expect(stationPanel.locator("#settings-station-select")).toHaveValue("station-night");
  await expect(stationPanel.locator("#programming-horizon")).toBeVisible();

  await stationPanel.locator("#programming-horizon").fill("45");
  await stationPanel.getByRole("button", { name: "Save Policy", exact: true }).click();

  await expect.poll(() => programmingUpdateRequests.length).toBe(1);
  await expect(programmingUpdateRequests[0]?.headers["x-admin-token"]).toBe("playwright-admin");
  await expect(programmingUpdateRequests[0]?.body).toMatchObject({
    version: 3,
    planningHorizonMinutes: 45,
    enabled: true,
  });
  await expect(stationPanel).toContainText("番組編成ポリシーを保存しました。");
});

test("settings: ProgramTemplate duplicate draft -> update existing", async ({ page }) => {
  const state = createSettingsState();
  const templateUpdateRequests: RequestCapture[] = [];

  await installSettingsRoutes(page, state, { templateUpdateRequests });
  await page.goto(appUrl("/settings"));

  const templatePanel = panelByHeading(page, "Program templates");

  await templatePanel.getByRole("button", { name: "Duplicate Current", exact: true }).click();
  await expect(templatePanel.locator("#template-id")).toHaveValue("tmpl-night-copy");
  await expect(templatePanel.locator("#template-name")).toHaveValue("Night Talk Copy");
  await templatePanel.getByRole("button", { name: "Close Draft", exact: true }).click();

  await expect(templatePanel.locator("#program-template-select")).toHaveValue("tmpl-night");
  await templatePanel.locator("#template-name").fill("Night Talk Updated");
  await templatePanel.getByRole("button", { name: "Add Slot", exact: true }).click();
  await templatePanel.locator("#template-slot-id-1").fill("letter-main");
  await templatePanel.locator("#template-slot-role-1").selectOption("LETTER");
  await templatePanel.locator("#template-slot-duration-1").fill("120000");
  await templatePanel.getByRole("button", { name: "Save Template", exact: true }).click();

  await expect.poll(() => templateUpdateRequests.length).toBe(1);
  await expect(templateUpdateRequests[0]?.headers["x-admin-token"]).toBe("playwright-admin");
  await expect(templateUpdateRequests[0]?.body).toMatchObject({
    id: "tmpl-night",
    name: "Night Talk Updated",
  });
  await expect((templateUpdateRequests[0]?.body.slots as Array<Record<string, unknown>> | undefined)?.map((slot) => slot.slotId)).toEqual([
    "opening",
    "letter-main",
  ]);
  await expect(templatePanel).toContainText("ProgramTemplate を保存しました。");
});

function createSettingsState() {
  const settings = buildSettingsResponse();
  const stations = [buildStation()];
  const stationDetails = new Map<string, Record<string, unknown>>([
    ["station-night", buildStationDetail()],
  ]);
  const stationProgramming = new Map<string, Record<string, unknown>>([
    ["station-night", buildStationProgrammingResponse()],
  ]);
  const templateDetails = new Map<string, Record<string, unknown>>([
    ["tmpl-night", buildProgramTemplateDetail()],
    [
      "tmpl-global-fallback",
      buildProgramTemplateDetail({
        id: "tmpl-global-fallback",
        scope: "GLOBAL",
        stationId: null,
        name: "Global Fallback",
        version: 1,
        targetDurationMinutes: 15,
        planningHorizonMinutes: 10,
        fallbackTemplateId: null,
        editorialPolicy: {
          tone: "safe",
        },
      }),
    ],
  ]);

  const templateSummaries = Array.from(templateDetails.values()).map(toTemplateSummary);

  return {
    settings,
    stations,
    stationDetails,
    stationProgramming,
    templateDetails,
    templateSummaries,
  };
}

async function installSettingsRoutes(
  page: Page,
  state: ReturnType<typeof createSettingsState>,
  captures: {
    stationCreateRequests?: RequestCapture[];
    programmingUpdateRequests?: RequestCapture[];
    templateCreateRequests?: RequestCapture[];
    templateUpdateRequests?: RequestCapture[];
  },
) {
  await page.route(apiUrl("/api/settings"), async (route) => {
    await fulfillJson(route, state.settings);
  });

  await page.route(apiUrl("/api/stations"), async (route) => {
    if (route.request().method() === "GET") {
      await fulfillJson(route, state.stations);
      return;
    }

    const body = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
    captures.stationCreateRequests?.push({ body, headers: route.request().headers() });

    const stationId = String(body.id);
    const stationDetail = buildStationDetail({
      ...body,
      id: stationId,
      version: 1,
      programming: buildStationDetail().programming,
    });
    const programming = buildStationProgrammingResponse({
      stationId,
      version: 1,
      enabled: false,
      defaultTemplateId: null,
      planningHorizonMinutes: 30,
      rules: [],
    });

    state.stations = [...state.stations, buildStation({
      id: stationId,
      name: String(body.name),
      frequencyMHz: Number(body.frequencyMHz),
      genre: String(body.genre),
      isActive: Boolean(body.isActive),
      programmingEnabled: false,
      defaultProgramTemplateId: null,
    })];
    state.stationDetails.set(stationId, stationDetail);
    state.stationProgramming.set(stationId, programming);

    await fulfillJson(route, {
      ...body,
      version: 1,
      updatedAt: "2026-04-25T00:00:00Z",
    });
  });

  await page.route(apiRegExp("/api/stations/[^/]+$"), async (route) => {
    const stationId = decodeURIComponent(route.request().url().split("/api/stations/")[1] ?? "");
    await fulfillJson(route, state.stationDetails.get(stationId));
  });

  await page.route(apiRegExp("/api/stations/[^/]+/programming$"), async (route) => {
    const stationId = decodeURIComponent(route.request().url().split("/api/stations/")[1]?.replace("/programming", "") ?? "");
    if (route.request().method() === "GET") {
      await fulfillJson(route, state.stationProgramming.get(stationId));
      return;
    }

    const body = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
    captures.programmingUpdateRequests?.push({ body, headers: route.request().headers() });
    const current = state.stationProgramming.get(stationId) ?? buildStationProgrammingResponse({ stationId, enabled: false, defaultTemplateId: null, rules: [] });
    const updated = {
      ...current,
      ...body,
      stationId,
      updatedAt: "2026-04-25T00:10:00Z",
      rules: (body.rules as unknown[]) ?? current.rules,
    };
    state.stationProgramming.set(stationId, updated);
    await fulfillJson(route, updated);
  });

  await page.route(apiUrl("/api/program-templates"), async (route) => {
    if (route.request().method() === "GET") {
      await fulfillJson(route, state.templateSummaries);
      return;
    }

    const body = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
    captures.templateCreateRequests?.push({ body, headers: route.request().headers() });
    const templateId = String(body.id);
    const detail = {
      ...body,
      id: templateId,
      version: 1,
    };
    state.templateDetails.set(templateId, detail);
    state.templateSummaries = upsertTemplateSummary(state.templateSummaries, detail);
    await fulfillJson(route, detail);
  });

  await page.route(apiRegExp("/api/program-templates/.*"), async (route) => {
    const templateId = decodeURIComponent(route.request().url().split("/api/program-templates/")[1] ?? "");
    if (route.request().method() === "GET") {
      await fulfillJson(route, state.templateDetails.get(templateId));
      return;
    }

    const body = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
    captures.templateUpdateRequests?.push({ body, headers: route.request().headers() });
    const updated = {
      ...body,
      id: templateId,
      version: Number(body.version ?? 0) + 1,
    };
    state.templateDetails.set(templateId, updated);
    state.templateSummaries = upsertTemplateSummary(state.templateSummaries, updated);
    await fulfillJson(route, updated);
  });
}

function upsertTemplateSummary(summaries: TemplateSummaryState[], detail: Record<string, unknown>): TemplateSummaryState[] {
  const next = toTemplateSummary(detail);
  const existingIndex = summaries.findIndex((entry) => entry.id === next.id);
  if (existingIndex < 0) {
    return [...summaries, next];
  }
  return summaries.map((entry, index) => (index === existingIndex ? next : entry));
}

function toTemplateSummary(detail: Record<string, unknown>): TemplateSummaryState {
  return buildProgramTemplateSummary({
    id: detail.id,
    scope: detail.scope,
    stationId: detail.stationId,
    name: detail.name,
    version: detail.version,
    targetDurationMinutes: detail.targetDurationMinutes,
    planningHorizonMinutes: detail.planningHorizonMinutes,
    isActive: detail.isActive,
    fallbackTemplateId: detail.fallbackTemplateId,
  });
}
