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
  loginAdmin,
  mockUnavailableStream,
  panelByHeading,
} from "./fixtures";

type RequestCapture = {
  body: Record<string, unknown>;
  headers: Record<string, string>;
};

type FailureResponse = {
  status: 400 | 409;
  body: Record<string, unknown>;
};

type TemplateSummaryState = ReturnType<typeof buildProgramTemplateSummary>;

test.beforeEach(async ({ page }) => {
  await clearPersistedUiState(page);
  await loginAdmin(page);
  await mockUnavailableStream(page);
});

test("settings: category navigation separates each responsibility", async ({ page }) => {
  const state = createSettingsState();
  await installSettingsRoutes(page, state, {});

  await page.goto(appUrl("/settings"));
  await expect(page.getByRole("heading", { name: "システム全体の状況" })).toBeVisible();
  const settingsNavigation = page.getByRole("navigation", { name: "管理カテゴリー" });
  await expect(settingsNavigation.getByRole("link", { name: /^システム/ })).toHaveAttribute("href", "/settings/system");
  await expect(settingsNavigation.getByRole("link", { name: /AI・音声接続/ })).toHaveAttribute("href", "/settings/providers");
  await expect(settingsNavigation.getByRole("link", { name: /再生・生成/ })).toHaveAttribute("href", "/settings/playout");
  await expect(settingsNavigation.locator('a[href="/settings/stations"]')).toContainText("局");
  await expect(settingsNavigation.getByRole("link", { name: /番組編成/ })).toHaveAttribute("href", "/settings/programming");
  await expect(settingsNavigation.getByRole("link", { name: /コンテンツ/ })).toHaveAttribute("href", "/settings/content");

  await page.goto(appUrl("/settings/system"));
  await expect(page.getByRole("heading", { name: "システム設定" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "局の管理" })).toHaveCount(0);

  await page.goto(appUrl("/settings/providers"));
  await expect(page.getByRole("heading", { name: "AI・音声接続", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "接続確認の結果" })).toBeVisible();

  await page.goto(appUrl("/settings/playout"));
  await expect(page.getByRole("heading", { name: "再生・生成設定" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "キューと先読み" })).toBeVisible();

  await page.goto(appUrl("/settings/stations"));
  await expect(page.getByRole("heading", { name: "局の管理" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "番組テンプレート" })).toHaveCount(0);

  await page.goto(appUrl("/settings/programming"));
  await expect(page.getByRole("heading", { name: "局ごとの番組編成ポリシー" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "番組テンプレート" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "編成結果を確認" })).toBeVisible();
});

test("settings: station inventory can request off-air pre-generation", async ({ page }) => {
  const state = createSettingsState();
  const preGenerationRequests: RequestCapture[] = [];
  await installSettingsRoutes(page, state, { preGenerationRequests });

  await page.goto(appUrl("/settings/content"));

  await expect(page.getByRole("heading", { name: "局別コンテンツ管理" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "Nocturne FM" })).toBeVisible();
  await expect(page.getByText("8 件", { exact: true }).first()).toBeVisible();
  await page.getByRole("button", { name: "事前生成を開始", exact: true }).click();

  await expect.poll(() => preGenerationRequests.length).toBe(1);
  await expect(preGenerationRequests[0]?.body).toMatchObject({
    programTemplateId: null,
    targetProgramCount: 1,
    includeSpeech: true,
    includeMusic: true,
  });
  await expect(page.getByRole("status")).toContainText("事前生成を受け付けました");
});

test("settings: station inventory can delete selected pre-generated assets", async ({ page }) => {
  const state = createSettingsState();
  const contentDeletionRequests: RequestCapture[] = [];
  await installSettingsRoutes(page, state, { contentDeletionRequests });

  await page.goto(appUrl("/settings/content"));
  await page.getByLabel("台本 (5 件)").uncheck();
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "選択したデータを削除" }).click();

  await expect.poll(() => contentDeletionRequests.length).toBe(1);
  await expect(contentDeletionRequests[0]?.body).toEqual({
    assetTypes: ["AUDIO", "MUSIC"],
  });
  await expect(page.getByRole("status")).toContainText("2 件を削除し、台帳容量を 4.00 KB 減らしました");
});

test("settings: Ollama connection method and model are editable with understandable URL validation", async ({ page }) => {
  const state = createSettingsState();
  const settingsUpdateRequests: RequestCapture[] = [];
  await installSettingsRoutes(page, state, { settingsUpdateRequests });
  await page.goto(appUrl("/settings/providers"));

  await expect(page.locator("#llm-ollama-adapter")).toHaveValue("OLLAMA");
  await expect(page.locator("#llm-ollama-defaultModel")).toHaveValue("qwen3:8b");

  await page.locator("#llm-ollama-baseUrl").fill("http://192..168.0.30:11434");
  await page.getByRole("button", { name: "このカテゴリーの変更を保存", exact: true }).click();
  await expect(page.getByText("LLM「ollama」の接続先 URL が正しくありません。")).toBeVisible();
  expect(settingsUpdateRequests).toHaveLength(0);

  await page.locator("#llm-ollama-baseUrl").fill("http://127.0.0.1:11434");
  await page.locator("#llm-ollama-defaultModel").fill("gemma3:4b");
  await page.getByRole("button", { name: "このカテゴリーの変更を保存", exact: true }).click();

  await expect.poll(() => settingsUpdateRequests.length).toBe(1);
  await expect(settingsUpdateRequests[0]?.body).toMatchObject({
    providers: {
      llm: {
        providers: {
          ollama: {
            baseUrl: "http://127.0.0.1:11434",
            adapter: "OLLAMA",
            defaultModelProfileId: "gemma3:4b",
          },
        },
      },
    },
  });
});

test("settings: existing station programming policy save", async ({ page }) => {
  const state = createSettingsState();
  const programmingUpdateRequests: RequestCapture[] = [];

  await installSettingsRoutes(page, state, { programmingUpdateRequests });
  await page.goto(appUrl("/settings/programming"));

  const stationPanel = panelByHeading(page, "局ごとの番組編成ポリシー");

  await expect(stationPanel.locator("#settings-station-select")).toHaveValue("station-night");
  await expect(stationPanel.locator("#programming-horizon")).toBeVisible();

  await stationPanel.locator("#programming-horizon").fill("45");
  await stationPanel.getByRole("button", { name: "番組編成を保存", exact: true }).click();

  await expect.poll(() => programmingUpdateRequests.length).toBe(1);
  expectBrowserAdminHeaders(programmingUpdateRequests[0]?.headers);
  await expect(programmingUpdateRequests[0]?.body).toMatchObject({
    version: 3,
    planningHorizonMinutes: 45,
    enabled: true,
  });
  await expect(stationPanel).toContainText("番組編成ポリシーを保存しました。");
});

test("settings: station create draft -> save and select new station", async ({ page }) => {
  const state = createSettingsState();
  const stationCreateRequests: RequestCapture[] = [];

  await installSettingsRoutes(page, state, { stationCreateRequests });
  await page.goto(appUrl("/settings/stations"));

  const stationPanel = panelByHeading(page, "局の管理");

  await stationPanel.getByRole("button", { name: "新しい局を作成", exact: true }).click();

  await expect(stationPanel.locator("#settings-station-select")).toHaveValue("");
  await expect(stationPanel.locator("#station-id")).toBeEditable();
  await expect(stationPanel.locator("#programming-horizon")).toHaveCount(0);

  await stationPanel.locator("#station-id").fill("station-dawn");
  await stationPanel.locator("#station-name").fill("Dawn Wave");
  await stationPanel.locator("#station-frequency").fill("80.0");
  await stationPanel.locator("#station-genre").fill("Morning Talk");
  await stationPanel.locator("#station-persona").fill("persona-dawn");
  await stationPanel.locator("#station-voice").fill("voice-dawn");
  await stationPanel.getByRole("button", { name: "局を作成", exact: true }).click();

  await expect.poll(() => stationCreateRequests.length).toBe(1);
  expectBrowserAdminHeaders(stationCreateRequests[0]?.headers);
  await expect(stationCreateRequests[0]?.body).toMatchObject({
    version: 0,
    id: "station-dawn",
    name: "Dawn Wave",
    frequencyMHz: 80.0,
    genre: "Morning Talk",
    languagePersonaId: "persona-dawn",
    defaultVoiceProfileId: "voice-dawn",
    isActive: true,
    programmingEnabled: false,
    defaultProgramTemplateId: null,
  });
  await expect(stationPanel.locator("#settings-station-select")).toHaveValue("station-dawn");
  await expect(stationPanel.locator("#station-id")).toHaveValue("station-dawn");
  await expect(stationPanel.locator("#station-id")).not.toBeEditable();
  await expect(stationPanel.locator("#programming-horizon")).toHaveCount(0);
  await expect(stationPanel).toContainText("局を作成しました。");
});

test("settings: station duplicate draft strips station policy and can be closed", async ({ page }) => {
  const state = createSettingsState();
  const stationCreateRequests: RequestCapture[] = [];

  await installSettingsRoutes(page, state, { stationCreateRequests });
  await page.goto(appUrl("/settings/stations"));

  const stationPanel = panelByHeading(page, "局の管理");

  await stationPanel.getByRole("button", { name: "選択中の局を複製", exact: true }).click();

  await expect(stationPanel.locator("#settings-station-select")).toHaveValue("");
  await expect(stationPanel.locator("#station-id")).toHaveValue("station-night-copy");
  await expect(stationPanel.locator("#station-name")).toHaveValue("Nocturne FM Copy");
  await expect(stationPanel.locator("#station-frequency")).toHaveValue("76.2");
  await expect(stationPanel.locator("#programming-horizon")).toHaveCount(0);

  await stationPanel.locator("#station-name").fill("Nocturne FM Draft");
  page.once("dialog", async (dialog) => {
    expect(dialog.message()).toContain("未保存の station 変更を破棄します。station draft のクローズを続行しますか？");
    await dialog.accept();
  });
  await stationPanel.getByRole("button", { name: "作成を中止", exact: true }).click();
  await expect(stationPanel.locator("#settings-station-select")).toHaveValue("station-night");
  await expect(stationPanel.locator("#station-id")).toHaveValue("station-night");

  await stationPanel.getByRole("button", { name: "選択中の局を複製", exact: true }).click();
  await stationPanel.locator("#station-id").fill("station-night-clone");
  await stationPanel.locator("#station-name").fill("Nocturne FM Clone");
  await stationPanel.getByRole("button", { name: "局を作成", exact: true }).click();

  await expect.poll(() => stationCreateRequests.length).toBe(1);
  expectBrowserAdminHeaders(stationCreateRequests[0]?.headers);
  await expect(stationCreateRequests[0]?.body).toMatchObject({
    version: 0,
    id: "station-night-clone",
    name: "Nocturne FM Clone",
    frequencyMHz: 76.2,
    genre: "Talk",
    languagePersonaId: "persona-night",
    defaultVoiceProfileId: "voice-night",
    isActive: true,
    programmingEnabled: false,
    defaultProgramTemplateId: null,
  });
  await expect(stationPanel.locator("#settings-station-select")).toHaveValue("station-night-clone");
  await expect(stationPanel.locator("#programming-horizon")).toHaveCount(0);
  await expect(stationPanel).toContainText("局を作成しました。");
});

test("settings: preview uses unsaved programming and template drafts", async ({ page }) => {
  const state = createSettingsState();
  const previewRequests: RequestCapture[] = [];

  await installSettingsRoutes(page, state, { previewRequests });
  await page.goto(appUrl("/settings/programming"));

  const stationPanel = panelByHeading(page, "局ごとの番組編成ポリシー");
  const templatePanel = panelByHeading(page, "番組テンプレート");
  const previewPanel = panelByHeading(page, "編成結果を確認");

  await stationPanel.locator("#programming-default-template").selectOption("tmpl-global-fallback");
  await templatePanel.locator("#program-template-select").selectOption("tmpl-global-fallback");
  await templatePanel.locator("#template-name").fill("Global Fallback Draft");
  await templatePanel.getByRole("button", { name: "構成枠を追加", exact: true }).click();
  await templatePanel.locator("#template-slot-id-1").fill("draft-letter");
  await templatePanel.locator("#template-slot-role-1").selectOption("LETTER");
  await templatePanel.locator("#template-slot-duration-1").fill("90000");

  await expect(previewPanel).toContainText("未保存の局別編成を使用");
  await expect(previewPanel).toContainText("未保存のテンプレート tmpl-global-fallback を使用");

  await previewPanel.locator("#preview-pending-letters").fill("2");
  await previewPanel.getByRole("button", { name: "この条件で編成を確認", exact: true }).click();

  await expect.poll(() => previewRequests.length).toBe(1);
  expectBrowserAdminHeaders(previewRequests[0]?.headers);
  await expect(previewRequests[0]?.body.policyDraft).toMatchObject({
    defaultTemplateId: "tmpl-global-fallback",
    version: 3,
  });
  await expect(previewRequests[0]?.body.templateDraft).toMatchObject({
    id: "tmpl-global-fallback",
    name: "Global Fallback Draft",
  });
  const previewTemplateDraft = previewRequests[0]?.body.templateDraft as { slots?: Array<Record<string, unknown>> } | undefined;
  await expect(previewTemplateDraft?.slots?.map((slot) => slot.slotId)).toEqual([
    "opening",
    "draft-letter",
  ]);
  await expect(previewPanel).toContainText("Global Fallback Draft");
  await expect(previewPanel).toContainText("draft-letter");
});

test("settings: ProgramTemplate blank create -> save and select new template", async ({ page }) => {
  const state = createSettingsState();
  const templateCreateRequests: RequestCapture[] = [];

  await installSettingsRoutes(page, state, { templateCreateRequests });
  await page.goto(appUrl("/settings/programming"));

  const stationPanel = panelByHeading(page, "局ごとの番組編成ポリシー");
  const templatePanel = panelByHeading(page, "番組テンプレート");
  const createButton = templatePanel.getByRole("button", { name: "テンプレートを作成", exact: true });

  await expect(templatePanel.locator("#program-template-select")).toHaveValue("tmpl-night");

  await templatePanel.getByRole("button", { name: "新しいテンプレート", exact: true }).click();

  await expect(templatePanel.locator("#program-template-select")).toHaveValue("");
  await expect(templatePanel.locator("#template-id")).toHaveValue("");
  await expect(templatePanel.locator("#template-id")).toBeEditable();
  await expect(templatePanel.locator("#template-name")).toHaveValue("");
  await expect(templatePanel.locator("#template-scope")).toHaveValue("STATION");
  await expect(templatePanel.locator("#template-station")).toHaveValue("station-night");
  await expect(templatePanel.locator("#template-duration")).toHaveValue("15");
  await expect(templatePanel.locator("#template-horizon")).toHaveValue("10");
  await expect(templatePanel.locator("#template-slot-id-0")).toHaveValue("opening");
  await expect(templatePanel).toContainText("Template ID は必須です。");
  await expect(templatePanel).toContainText("Template 名は必須です。");
  await expect(createButton).toBeDisabled();

  await templatePanel.locator("#template-id").fill("tmpl-night-blank");
  await templatePanel.locator("#template-name").fill("Night Blank");

  await expect(templatePanel).not.toContainText("Template ID は必須です。");
  await expect(templatePanel).not.toContainText("Template 名は必須です。");
  await expect(createButton).toBeEnabled();

  await createButton.click();

  await expect.poll(() => templateCreateRequests.length).toBe(1);
  expectBrowserAdminHeaders(templateCreateRequests[0]?.headers);
  await expect(templateCreateRequests[0]?.body).toMatchObject({
    version: 0,
    id: "tmpl-night-blank",
    scope: "STATION",
    stationId: "station-night",
    name: "Night Blank",
    targetDurationMinutes: 15,
    planningHorizonMinutes: 10,
    isActive: true,
    fallbackTemplateId: null,
    editorialPolicy: {},
  });
  await expect(templateCreateRequests[0]?.body.slots).toEqual([
    {
      slotId: "opening",
      role: "OPENING",
      constraintMode: "HARD",
      candidateSegmentTypes: ["JINGLE", "TALK"],
      fallbackSegmentTypes: ["TALK"],
      targetDurationMs: 30000,
      slotPolicy: {},
    },
  ]);
  await expect(templatePanel.locator("#program-template-select")).toHaveValue("tmpl-night-blank");
  await expect(templatePanel.locator("#program-template-select option[value='tmpl-night-blank']")).toContainText("Night Blank");
  await expect(templatePanel.locator("#template-id")).toHaveValue("tmpl-night-blank");
  await expect(templatePanel.locator("#template-id")).not.toBeEditable();
  await expect(templatePanel.locator("#template-name")).toHaveValue("Night Blank");
  await expect(templatePanel.getByRole("button", { name: "テンプレートを保存", exact: true })).toBeVisible();
  await expect(stationPanel.locator("#programming-default-template option[value='tmpl-night-blank']")).toContainText("Night Blank");
  await expect(templatePanel).toContainText("ProgramTemplate を作成しました。");
});

test("settings: ProgramTemplate create shows safe conflict message on 409", async ({ page }) => {
  const state = createSettingsState();
  const templateCreateRequests: RequestCapture[] = [];

  await installSettingsRoutes(page, state, {
    templateCreateRequests,
    templateCreateFailure: {
      status: 409,
      body: {
        code: "CONFLICT",
        message: "duplicate template id: tmpl-night-secret authorization: Bearer super-secret-token",
        details: {
          fieldErrors: {
            id: "tmpl-night-secret is already used",
          },
        },
      },
    },
  });
  await page.goto(appUrl("/settings/programming"));

  const templatePanel = panelByHeading(page, "番組テンプレート");

  await templatePanel.getByRole("button", { name: "新しいテンプレート", exact: true }).click();
  await templatePanel.locator("#template-id").fill("tmpl-night-secret");
  await templatePanel.locator("#template-name").fill("Night Secret");
  await templatePanel.getByRole("button", { name: "テンプレートを作成", exact: true }).click();

  await expect.poll(() => templateCreateRequests.length).toBe(1);
  await expect(templatePanel).toContainText(
    "同じ Template ID の ProgramTemplate が既に存在するため作成できません。別の Template ID に変更して再度保存してください。",
  );
  await expect(templatePanel).not.toContainText("authorization: Bearer super-secret-token");
  await expect(templatePanel).toContainText("id: tmpl-night-secret is already used");
  await expect(templatePanel.locator("#program-template-select")).toHaveValue("");
  await expect(templatePanel.locator("#template-id")).toHaveValue("tmpl-night-secret");
  await expect(templatePanel.locator("#template-id")).toBeEditable();
});

test("settings: ProgramTemplate duplicate draft -> update existing", async ({ page }) => {
  const state = createSettingsState();
  const templateUpdateRequests: RequestCapture[] = [];

  await installSettingsRoutes(page, state, { templateUpdateRequests });
  await page.goto(appUrl("/settings/programming"));

  const templatePanel = panelByHeading(page, "番組テンプレート");

  await templatePanel.getByRole("button", { name: "選択中のテンプレートを複製", exact: true }).click();
  await expect(templatePanel.locator("#template-id")).toHaveValue("tmpl-night-copy");
  await expect(templatePanel.locator("#template-name")).toHaveValue("Night Talk Copy");
  await templatePanel.getByRole("button", { name: "作成を中止", exact: true }).click();

  await expect(templatePanel.locator("#program-template-select")).toHaveValue("tmpl-night");
  await templatePanel.locator("#template-name").fill("Night Talk Updated");
  await templatePanel.getByRole("button", { name: "構成枠を追加", exact: true }).click();
  await templatePanel.locator("#template-slot-id-1").fill("letter-main");
  await templatePanel.locator("#template-slot-role-1").selectOption("LETTER");
  await templatePanel.locator("#template-slot-duration-1").fill("120000");
  await templatePanel.getByRole("button", { name: "テンプレートを保存", exact: true }).click();

  await expect.poll(() => templateUpdateRequests.length).toBe(1);
  expectBrowserAdminHeaders(templateUpdateRequests[0]?.headers);
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

test("settings: ProgramTemplate update shows safe validation message on 400", async ({ page }) => {
  const state = createSettingsState();
  const templateUpdateRequests: RequestCapture[] = [];

  await installSettingsRoutes(page, state, {
    templateUpdateRequests,
    templateUpdateFailure: {
      status: 400,
      body: {
        code: "VALIDATION_ERROR",
        message: "candidateSegmentTypes contains unsupported value SECRET_SEGMENT and token=abc123",
        details: {
          fieldErrors: {
            "slots[0].candidateSegmentTypes": "SECRET_SEGMENT is unsupported",
          },
        },
      },
    },
  });
  await page.goto(appUrl("/settings/programming"));

  const templatePanel = panelByHeading(page, "番組テンプレート");

  await templatePanel.locator("#template-name").fill("Night Talk Validation");
  await templatePanel.getByRole("button", { name: "テンプレートを保存", exact: true }).click();

  await expect.poll(() => templateUpdateRequests.length).toBe(1);
  await expect(templatePanel).toContainText(
    "ProgramTemplate を保存できませんでした。scope / station / slot / segmentType の整合を見直してから再度保存してください。",
  );
  await expect(templatePanel).toContainText("slots[0].candidateSegmentTypes: SECRET_SEGMENT is unsupported");
  await expect(templatePanel).not.toContainText("token=abc123");
  await expect(templatePanel.locator("#program-template-select")).toHaveValue("tmpl-night");
  await expect(templatePanel.locator("#template-name")).toHaveValue("Night Talk Validation");
  await expect(templatePanel).not.toContainText("ProgramTemplate を保存しました。");
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

function buildManagementDashboard() {
  return {
    system: {
      sessionId: "session-night-001",
      stationId: "station-night",
      state: "PLAYING",
      bufferReadyCount: 3,
      queueReadyDurationMs: 180000,
      pendingLetterCount: 1,
      degraded: false,
      providerHealth: {
        LLM: {
          providerType: "LLM",
          providerKey: "ollama",
          status: "UP",
          lastCheckedAt: "2026-07-26T00:00:00Z",
          responseTimeMs: 32,
          message: "利用できます。",
          capabilities: ["SCRIPT_GEN"],
          baseUrl: null,
        },
      },
      cache: {
        checkedAt: "2026-07-26T00:00:00Z",
        assetCount: 15,
        byteSize: 5242880,
        cacheHitCount: 4,
        cacheHitRate: 0.25,
        expiredAssetCount: 0,
        byType: {},
      },
      archive: {
        eligibleArchiveCount: 0,
        totalArchiveCount: 0,
        archiveReplayCount: 0,
        totalPlaybackCount: 0,
        archiveReplayRate: 0,
      },
      runningJobs: [],
      recentJobs: [],
      recentErrors: [],
      auditEvents: [],
      updatedAt: "2026-07-26T00:00:00Z",
    },
    stationCount: 1,
    activeStationCount: 1,
    programTemplateCount: 2,
    stations: [
      {
        stationId: "station-night",
        stationName: "Nocturne FM",
        active: true,
        programmingEnabled: true,
        applicableProgramTemplateCount: 2,
        programCount: 8,
        preGeneratedProgramCount: 3,
        generatedAssetCount: 15,
        generatedAssetBytes: 5242880,
        scriptAssetCount: 5,
        audioAssetCount: 6,
        musicAssetCount: 4,
        musicAssetBytes: 4194304,
        latestProgramAt: "2026-07-26T00:00:00Z",
        latestAssetAt: "2026-07-26T00:00:00Z",
        latestPreGeneration: null,
      },
    ],
    recentPreGenerations: [],
    updatedAt: "2026-07-26T00:00:00Z",
  };
}

async function installSettingsRoutes(
  page: Page,
  state: ReturnType<typeof createSettingsState>,
  captures: {
    settingsUpdateRequests?: RequestCapture[];
    stationCreateRequests?: RequestCapture[];
    programmingUpdateRequests?: RequestCapture[];
    previewRequests?: RequestCapture[];
    templateCreateRequests?: RequestCapture[];
    templateUpdateRequests?: RequestCapture[];
    preGenerationRequests?: RequestCapture[];
    contentDeletionRequests?: RequestCapture[];
    templateCreateFailure?: FailureResponse;
    templateUpdateFailure?: FailureResponse;
  },
) {
  await page.route(apiUrl("/api/management/dashboard"), async (route) => {
    await fulfillJson(route, buildManagementDashboard());
  });
  await page.route(apiRegExp("/api/management/stations/[^/]+/pre-generations$"), async (route) => {
    const body = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
    captures.preGenerationRequests?.push({ body, headers: route.request().headers() });
    await fulfillJson(route, {
      id: "pregen-e2e-001",
      stationId: "station-night",
      sessionId: "playout-pregen-e2e-001",
      programTemplateId: body.programTemplateId ?? null,
      targetProgramCount: body.targetProgramCount ?? 1,
      includeSpeech: body.includeSpeech ?? true,
      includeMusic: body.includeMusic ?? true,
      status: "QUEUED",
      materializedProgramCount: 0,
      materializedSegmentCount: 0,
      queuedMusicCount: 0,
      errorCode: null,
      requestedAt: "2026-07-26T00:00:00Z",
      startedAt: null,
      completedAt: null,
      updatedAt: "2026-07-26T00:00:00Z",
    }, 202);
  });
  await page.route(apiRegExp("/api/management/stations/[^/]+/content/deletions$"), async (route) => {
    const body = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
    captures.contentDeletionRequests?.push({ body, headers: route.request().headers() });
    await fulfillJson(route, {
      stationId: "station-night",
      executedAt: "2026-07-28T12:00:00Z",
      candidateAssetCount: 2,
      deletedAssetCount: 2,
      failedAssetCount: 0,
      reclaimedBytes: 4096,
      deletedByType: { AUDIO: 1, MUSIC: 1 },
    });
  });
  await page.route(apiUrl("/api/settings"), async (route) => {
    if (route.request().method() === "PUT") {
      const body = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
      captures.settingsUpdateRequests?.push({ body, headers: route.request().headers() });
      state.settings = {
        ...state.settings,
        ...body,
        version: Number(body.version ?? state.settings.version) + 1,
        updatedAt: "2026-04-25T00:00:00Z",
      };
      await fulfillJson(route, state.settings);
      return;
    }
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
    const defaultTemplateId = typeof body.defaultProgramTemplateId === "string" ? body.defaultProgramTemplateId : null;
    const programmingEnabled = Boolean(body.programmingEnabled);
    const programming = buildStationProgrammingResponse({
      stationId,
      version: 1,
      enabled: programmingEnabled,
      defaultTemplateId,
      planningHorizonMinutes: 30,
      rules: [],
    });
    const stationDetail = buildStationDetail({
      ...body,
      id: stationId,
      version: 1,
      programming: {
        enabled: programming.enabled,
        defaultTemplateId: programming.defaultTemplateId,
        fallbackStrategy: programming.fallbackStrategy,
        planningHorizonMinutes: programming.planningHorizonMinutes,
        preGeneration: programming.preGeneration,
        replay: programming.replay,
        composition: programming.composition,
      },
    });

    state.stations = [...state.stations, buildStation({
      id: stationId,
      name: String(body.name),
      frequencyMHz: Number(body.frequencyMHz),
      genre: String(body.genre),
      isActive: Boolean(body.isActive),
      programmingEnabled,
      defaultProgramTemplateId: defaultTemplateId,
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
    const updated = buildStationProgrammingResponse({
      ...current,
      ...body,
      stationId,
      updatedAt: "2026-04-25T00:10:00Z",
      rules: (body.rules as unknown[]) ?? current.rules,
    });
    state.stationProgramming.set(stationId, updated);
    await fulfillJson(route, updated);
  });

  await page.route(apiRegExp("/api/stations/[^/]+/programming/preview$"), async (route) => {
    const stationId = decodeURIComponent(route.request().url().split("/api/stations/")[1]?.replace("/programming/preview", "") ?? "");
    const body = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
    captures.previewRequests?.push({ body, headers: route.request().headers() });

    const templateDraft = body.templateDraft as Record<string, unknown> | undefined;
    const selectedTemplateDetail = state.templateDetails.get(String((body.policyDraft as Record<string, unknown> | undefined)?.defaultTemplateId ?? "tmpl-night"));
    const slots = Array.isArray(templateDraft?.slots)
      ? templateDraft.slots
      : ((selectedTemplateDetail?.slots as unknown[] | undefined) ?? []);

    await fulfillJson(route, {
      stationId,
      selectedTemplateId: (body.policyDraft as Record<string, unknown> | undefined)?.defaultTemplateId ?? templateDraft?.id ?? "tmpl-night",
      fallbackApplied: false,
      program: {
        title: typeof templateDraft?.name === "string" ? templateDraft.name : "Night Talk",
        plannedDurationMs: slots.reduce((total, slot) => total + Number((slot as Record<string, unknown>).targetDurationMs ?? 0), 0),
      },
      slots: slots.map((slot) => ({
        slotId: (slot as Record<string, unknown>).slotId,
        role: (slot as Record<string, unknown>).role,
        constraintMode: (slot as Record<string, unknown>).constraintMode,
        targetDurationMs: (slot as Record<string, unknown>).targetDurationMs,
      })),
      validationWarnings: [],
    });
  });

  await page.route(apiUrl("/api/program-templates"), async (route) => {
    if (route.request().method() === "GET") {
      await fulfillJson(route, state.templateSummaries);
      return;
    }

    const body = JSON.parse(route.request().postData() ?? "{}") as Record<string, unknown>;
    captures.templateCreateRequests?.push({ body, headers: route.request().headers() });
    if (captures.templateCreateFailure) {
      await fulfillJson(route, captures.templateCreateFailure.body, captures.templateCreateFailure.status);
      return;
    }
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
    if (captures.templateUpdateFailure) {
      await fulfillJson(route, captures.templateUpdateFailure.body, captures.templateUpdateFailure.status);
      return;
    }
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

function expectBrowserAdminHeaders(headers: Record<string, string> | undefined) {
  expect(headers?.["x-admin-token"]).toBeUndefined();
  expect(headers?.["x-csrf-token"]).toBeTruthy();
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
