"use client";

import { useEffect, useRef, useState, type Dispatch, type ReactNode, type SetStateAction } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ApiRequestError,
  createProgramTemplate,
  createStation,
  getProgramTemplate,
  getSettings,
  getStation,
  getStationProgramming,
  loadAceStepModel,
  listProgramTemplates,
  listStations,
  previewProgramming,
  testConnections,
  updateProgramTemplate,
  updateStation,
  updateStationProgramming,
  updateSettings,
} from "@/lib/api";
import { formatSafeDisplayText, getSafeMetadataEntries, REDACTED_METADATA_VALUE } from "@/lib/safe-metadata";
import { buildSettingsExportFilename, buildSettingsExportPayload, parseSettingsImportPayload } from "@/lib/settings-import-export";
import { applyProgrammingSummaryToStationDraft, cloneStationDraft, createBlankStationDraft, createDuplicatedStationDraft } from "@/lib/station-editor";
import {
  cloneProgramTemplateDraft,
  createBlankProgramTemplateDraft,
  createDefaultSlotDraft,
  createDuplicatedProgramTemplateDraft,
  createProgramTemplateDraftFromDetail,
  serializeProgramTemplateDraft,
  type ProgramTemplateEditorDraft,
} from "@/lib/template-editor";
import type {
  AceStepModelLoadResponse,
  ConstraintMode,
  ConnectionsTestResponse,
  ProgramTemplateDetail,
  ProgramTemplateSummary,
  ProgrammingPreviewRequest,
  ProgrammingPreviewResponse,
  SegmentType,
  FeatureSettings,
  ProviderCatalog,
  ProviderEndpoint,
  ProviderGroup,
  ProviderHealthPayload,
  SettingsResponse,
  SettingsUpdateRequest,
  StationDetail,
  StationResponse,
  StationProgrammingResponse,
  StationProgrammingUpdateRequest,
  SlotRole,
  StationUpdateRequest,
  StationSummary,
} from "@/lib/types";
import { Badge, Button, Card, EmptyState, Input, Label, Metric, SectionHeader, Textarea } from "@/components/ui";
import { PanelColumn, PanelGrid } from "@/components/markdown";
import { useUiStore } from "@/stores/ui-store";

const REUSE_SCOPE_OPTIONS = ["DISABLED", "SESSION", "STATION", "GLOBAL", "ARCHIVE_ONLY"] as const;
const PRE_GENERATION_MODE_OPTIONS = ["REALTIME_ONLY", "ASSISTED", "AGGRESSIVE"] as const;
const REPLAY_INTENSITY_OPTIONS = ["OFF", "LIGHT", "MEDIUM", "HEAVY"] as const;
const SEGMENT_TYPE_OPTIONS = ["TALK", "LETTER", "JINGLE", "MUSIC_LOCAL", "MUSIC_AI"] as const;
const TEMPLATE_SCOPE_OPTIONS = ["GLOBAL", "STATION"] as const;
const SLOT_ROLE_OPTIONS = ["OPENING", "TOPIC", "LETTER", "MUSIC_BREAK", "ENDING"] as const;
const CONSTRAINT_MODE_OPTIONS = ["HARD", "SOFT"] as const;
const SHARE_KEYS = ["talk", "letter", "music", "jingle"] as const;
const DAY_OPTIONS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"] as const;
const REQUIRED_PROVIDER_STATE_OPTIONS = [
  "MUSICGEN_UP",
  "MUSICGEN_DEGRADED",
  "TTS_UP",
  "TTS_DEGRADED",
  "LLM_UP",
  "LLM_DEGRADED",
] as const;
const FALLBACK_STRATEGY_OPTIONS = ["LEGACY_RATIO"] as const;
const PROVIDER_GROUPS: Array<keyof ProviderCatalog> = ["llm", "tts", "musicGen"];
const PROVIDER_LABELS: Record<keyof ProviderCatalog, string> = {
  llm: "LLM",
  tts: "TTS",
  musicGen: "MusicGen",
};
const PROGRAMMING_STATE_OPTIONS = ["UP", "DEGRADED", "DOWN", "UNKNOWN"] as const;
const PROVIDER_ADAPTER_OPTIONS = ["MUSICGEN_WORKER", "ACE_STEP"] as const;
const LLM_PROVIDER_ADAPTER_OPTIONS = ["OLLAMA", "OPENAI_COMPATIBLE"] as const;
type ProviderEndpointField = "baseUrl" | "healthPath" | "timeoutMs" | "capabilities" | "adapter" | "apiKeyRef" | "defaultModelProfileId";
type ProviderEndpointValue = string | number | string[] | null;
const SELECT_CLASS_NAME =
  "field-control min-h-11 w-full rounded-2xl border border-slate-300 bg-white/90 px-4 py-3 text-sm text-slate-900 outline-none";

export type SettingsPage = "system" | "providers" | "playout" | "stations" | "programming";

const SETTINGS_PAGE_COPY: Record<Exclude<SettingsPage, "stations" | "programming">, { eyebrow: string; title: string; description: string }> = {
  system: {
    eyebrow: "System",
    title: "システム設定",
    description: "Server の待受先、データ保存先、管理認証、設定ファイルの入出力を管理します。ネットワーク公開範囲と秘密値の参照方法を確認してから保存してください。",
  },
  providers: {
    eyebrow: "AI Providers",
    title: "AI・音声接続",
    description: "台本生成、音声合成、音楽生成で使う Provider の接続先、既定 Provider、障害時の切替順を管理します。接続確認は保存済みの内容に対して実行されます。",
  },
  playout: {
    eyebrow: "Playout",
    title: "再生・生成設定",
    description: "途切れにくい放送を行うためのキュー先読み、生成量、キャッシュ保持、全局共通の編成既定値を管理します。変更は次の番組から反映されます。",
  },
};

export function SettingsDashboard({ page }: { page: SettingsPage }) {
  const queryClient = useQueryClient();
  const hasAdminToken = true;
  const selectedStationId = useUiStore((state) => state.selectedStationId);
  const setSelectedStationId = useUiStore((state) => state.setSelectedStationId);
  const [draft, setDraft] = useState<SettingsUpdateRequest | null>(null);
  const [saveNotice, setSaveNotice] = useState<string | null>(null);
  const [saveValidationError, setSaveValidationError] = useState<string | null>(null);
  const [importNotice, setImportNotice] = useState<string | null>(null);
  const [importError, setImportError] = useState<string | null>(null);
  const [selectedTemplateId, setSelectedTemplateId] = useState<string | null>(null);
  const [stationDraft, setStationDraft] = useState<StationUpdateRequest | null>(null);
  const [stationEditorMode, setStationEditorMode] = useState<"existing" | "create">("existing");
  const [stationCreateBaseDraft, setStationCreateBaseDraft] = useState<StationUpdateRequest | null>(null);
  const [stationCreateSourceId, setStationCreateSourceId] = useState<string | null>(null);
  const [stationNotice, setStationNotice] = useState<string | null>(null);
  const [templateDraft, setTemplateDraft] = useState<ProgramTemplateEditorDraft | null>(null);
  const [templateEditorMode, setTemplateEditorMode] = useState<"existing" | "create">("existing");
  const [templateCreateBaseDraft, setTemplateCreateBaseDraft] = useState<ProgramTemplateEditorDraft | null>(null);
  const [templateCreateSourceId, setTemplateCreateSourceId] = useState<string | null>(null);
  const [templateNotice, setTemplateNotice] = useState<string | null>(null);
  const [programmingDraft, setProgrammingDraft] = useState<StationProgrammingUpdateRequest | null>(null);
  const [programmingNotice, setProgrammingNotice] = useState<string | null>(null);
  const [previewDraft, setPreviewDraft] = useState(createPreviewDraft);
  const importInputRef = useRef<HTMLInputElement | null>(null);

  const settingsQuery = useQuery({
    queryKey: ["settings"],
    queryFn: getSettings,
    enabled: hasAdminToken,
    retry: false,
    refetchOnWindowFocus: false,
  });

  useEffect(() => {
    if (!settingsQuery.data) {
      return;
    }
    setDraft(createDraft(settingsQuery.data));
  }, [settingsQuery.data]);

  const saveMutation = useMutation({
    mutationFn: updateSettings,
    onMutate: () => {
      setSaveNotice(null);
      setSaveValidationError(null);
      setImportError(null);
    },
    onSuccess: (saved) => {
      queryClient.setQueryData(["settings"], saved);
      setDraft(createDraft(saved));
      setSaveNotice("設定を保存しました。接続テストは保存済みの内容に対して実行されます。");
      setImportNotice(null);
    },
  });

  const connectionsMutation = useMutation({
    mutationFn: testConnections,
  });
  const aceStepModelLoadMutation = useMutation({
    mutationFn: ({ providerKey, profileId }: { providerKey: string; profileId: string }) =>
      loadAceStepModel(providerKey, { profileId, slot: 1 }),
    onSuccess: () => {
      connectionsMutation.mutate();
    },
  });

  const stationsQuery = useQuery({
    queryKey: ["settings", "stations"],
    queryFn: listStations,
    enabled: hasAdminToken,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const stationDetailQuery = useQuery({
    queryKey: ["settings", "station", selectedStationId],
    queryFn: () => getStation(selectedStationId ?? ""),
    enabled: hasAdminToken && Boolean(selectedStationId),
    retry: false,
    refetchOnWindowFocus: false,
  });
  const stationProgrammingQuery = useQuery({
    queryKey: ["settings", "station-programming", selectedStationId],
    queryFn: () => getStationProgramming(selectedStationId ?? ""),
    enabled: hasAdminToken && Boolean(selectedStationId),
    retry: false,
    refetchOnWindowFocus: false,
  });
  const templatesQuery = useQuery({
    queryKey: ["settings", "program-templates"],
    queryFn: listProgramTemplates,
    enabled: hasAdminToken,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const templateDetailQuery = useQuery({
    queryKey: ["settings", "program-template", selectedTemplateId],
    queryFn: () => getProgramTemplate(selectedTemplateId ?? ""),
    enabled: hasAdminToken && Boolean(selectedTemplateId),
    retry: false,
    refetchOnWindowFocus: false,
  });
  const previewMutation = useMutation({
    mutationFn: () => {
      if (!selectedStationId) {
        throw new Error("Preview 対象の station を選択してください。");
      }
      return previewProgramming(
        selectedStationId,
        buildProgrammingPreviewRequest({
          stationId: selectedStationId,
          previewDraft,
          programmingDraft,
          savedProgramming: stationProgrammingQuery.data,
          isProgrammingDirty,
          templateDraft,
          templateEditorMode,
          isTemplateDirty,
        }),
      );
    },
  });
  const applyStationSave = (saved: StationResponse, mode: "create" | "update") => {
    queryClient.setQueryData<StationSummary[]>(["settings", "stations"], (current) => {
      const nextStation = {
        id: saved.id,
        name: saved.name,
        frequencyMHz: saved.frequencyMHz,
        genre: saved.genre,
        isActive: saved.isActive,
        programmingEnabled: saved.programmingEnabled,
        defaultProgramTemplateId: saved.defaultProgramTemplateId,
      };

      if (!current?.length) {
        return [nextStation];
      }
      if (current.some((station) => station.id === saved.id)) {
        return current.map((station) => (station.id === saved.id ? nextStation : station));
      }
      return [...current, nextStation];
    });
    setStationDraft({
      version: saved.version,
      id: saved.id,
      name: saved.name,
      frequencyMHz: saved.frequencyMHz,
      genre: saved.genre,
      languagePersonaId: saved.languagePersonaId,
      defaultVoiceProfileId: saved.defaultVoiceProfileId,
      isActive: saved.isActive,
      programmingEnabled: saved.programmingEnabled,
      defaultProgramTemplateId: saved.defaultProgramTemplateId,
    });
    setStationEditorMode("existing");
    setStationCreateBaseDraft(null);
    setStationCreateSourceId(null);
    setProgrammingDraft(null);
    setSelectedStationId(saved.id);
    setStationNotice(
      mode === "create"
        ? "局を作成しました。番組編成ポリシーは必要に応じて下の editor で調整してください。"
        : "局の基本情報を保存しました。表示と次回の番組計画に反映されます。",
    );
    void queryClient.invalidateQueries({ queryKey: ["settings", "station", saved.id] });
    void queryClient.invalidateQueries({ queryKey: ["settings", "station-programming", saved.id] });
    void queryClient.invalidateQueries({ queryKey: ["settings", "stations"] });
    void queryClient.invalidateQueries({ queryKey: ["stations"] });
  };
  const createStationMutation = useMutation({
    mutationFn: createStation,
    onMutate: () => {
      setStationNotice(null);
    },
    onSuccess: (saved) => {
      applyStationSave(saved, "create");
    },
  });
  const stationMutation = useMutation({
    mutationFn: ({ stationId, body }: { stationId: string; body: StationUpdateRequest }) => updateStation(stationId, body),
    onMutate: () => {
      setStationNotice(null);
    },
    onSuccess: (saved) => {
      applyStationSave(saved, "update");
    },
  });
  const programmingMutation = useMutation({
    mutationFn: ({ stationId, body }: { stationId: string; body: StationProgrammingUpdateRequest }) => updateStationProgramming(stationId, body),
    onMutate: () => {
      setProgrammingNotice(null);
    },
    onSuccess: (saved) => {
      queryClient.setQueryData<StationDetail | undefined>(["settings", "station", saved.stationId], (current) =>
        current
          ? {
              ...current,
              programming: {
                ...current.programming,
                enabled: saved.enabled,
                defaultTemplateId: saved.defaultTemplateId,
              },
            }
          : current,
      );
      queryClient.setQueryData<StationSummary[]>(["settings", "stations"], (current) =>
        current?.map((station) =>
          station.id === saved.stationId
            ? {
                ...station,
                programmingEnabled: saved.enabled,
                defaultProgramTemplateId: saved.defaultTemplateId,
              }
            : station,
        ) ?? current,
      );
      queryClient.setQueryData(["settings", "station-programming", saved.stationId], saved);
      setStationDraft((current) => (current ? applyProgrammingSummaryToStationDraft(current, saved) : current));
      setProgrammingDraft(createProgrammingDraft(saved));
      setProgrammingNotice("番組編成ポリシーを保存しました。変更は実行中 block ではなく次の番組から反映されます。");
      void queryClient.invalidateQueries({ queryKey: ["settings", "station", saved.stationId] });
      void queryClient.invalidateQueries({ queryKey: ["settings", "stations"] });
    },
  });
  const applyTemplateSave = (saved: ProgramTemplateDetail, mode: "create" | "update") => {
    queryClient.setQueryData<ProgramTemplateSummary[]>(["settings", "program-templates"], (current) => {
      const nextTemplate = {
        id: saved.id,
        scope: saved.scope,
        stationId: saved.stationId,
        name: saved.name,
        version: saved.version,
        targetDurationMinutes: saved.targetDurationMinutes,
        planningHorizonMinutes: saved.planningHorizonMinutes,
        isActive: saved.isActive,
        fallbackTemplateId: saved.fallbackTemplateId,
      };

      if (!current?.length) {
        return [nextTemplate];
      }
      if (current.some((template) => template.id === saved.id)) {
        return current.map((template) => (template.id === saved.id ? nextTemplate : template));
      }
      return [...current, nextTemplate];
    });
    queryClient.setQueryData(["settings", "program-template", saved.id], saved);
    setTemplateDraft(createProgramTemplateDraftFromDetail(saved));
    setTemplateEditorMode("existing");
    setTemplateCreateBaseDraft(null);
    setTemplateCreateSourceId(null);
    setSelectedTemplateId(saved.id);
    setTemplateNotice(
      mode === "create"
        ? "ProgramTemplate を作成しました。必要に応じて未保存の policy / template draft を含めて Preview を確認できます。"
        : "ProgramTemplate を保存しました。変更は実行中 block ではなく次の番組から反映されます。",
    );
    void queryClient.invalidateQueries({ queryKey: ["settings", "program-templates"] });
    void queryClient.invalidateQueries({ queryKey: ["settings", "program-template", saved.id] });
  };
  const createTemplateMutation = useMutation({
    mutationFn: createProgramTemplate,
    onMutate: () => {
      setTemplateNotice(null);
    },
    onSuccess: (saved) => {
      applyTemplateSave(saved, "create");
    },
  });
  const templateMutation = useMutation({
    mutationFn: ({ templateId, body }: { templateId: string; body: ReturnType<typeof serializeProgramTemplateDraft> }) =>
      updateProgramTemplate(templateId, body),
    onMutate: () => {
      setTemplateNotice(null);
    },
    onSuccess: (saved) => {
      applyTemplateSave(saved, "update");
    },
  });

  const baseDraft = settingsQuery.data ? createDraft(settingsQuery.data) : null;
  const isDirty = baseDraft !== null && draft !== null && JSON.stringify(baseDraft) !== JSON.stringify(draft);
  const baseStationDraft =
    stationEditorMode === "create" ? stationCreateBaseDraft : stationDetailQuery.data ? createStationDraft(stationDetailQuery.data) : null;
  const isStationDirty = baseStationDraft !== null && stationDraft !== null && JSON.stringify(baseStationDraft) !== JSON.stringify(stationDraft);
  const baseTemplateDraft =
    templateEditorMode === "create"
      ? templateCreateBaseDraft
      : templateDetailQuery.data
        ? createProgramTemplateDraftFromDetail(templateDetailQuery.data)
        : null;
  const isTemplateDirty = baseTemplateDraft !== null && templateDraft !== null && JSON.stringify(baseTemplateDraft) !== JSON.stringify(templateDraft);
  const baseProgrammingDraft = stationProgrammingQuery.data ? createProgrammingDraft(stationProgrammingQuery.data) : null;
  const isProgrammingDirty =
    baseProgrammingDraft !== null && programmingDraft !== null && JSON.stringify(baseProgrammingDraft) !== JSON.stringify(programmingDraft);
  const bindHostWarning = draft && isUnsafeBindHost(draft.server.bindHost);
  const previewUsesPolicyDraft = Boolean(selectedStationId && programmingDraft && isProgrammingDirty);
  const previewTemplateDraftId = selectedStationId
    ? resolvePreviewTemplateDraftId({
        stationId: selectedStationId,
        programming: previewUsesPolicyDraft ? (programmingDraft ?? undefined) : stationProgrammingQuery.data,
        templateDraft,
        isTemplateDirty,
        templateEditorMode,
      })
    : null;

  function confirmDiscardStationChanges(actionLabel: string) {
    if (!isStationDirty) {
      return true;
    }
    return window.confirm(`未保存の station 変更を破棄します。${actionLabel}を続行しますか？`);
  }

  const resetDraft = () => {
    if (!settingsQuery.data) {
      return;
    }
    setDraft(createDraft(settingsQuery.data));
    setSaveNotice("未保存の変更を破棄しました。");
    setImportNotice(null);
    setImportError(null);
    setSaveValidationError(null);
  };

  const updateDraft = (updater: (current: SettingsUpdateRequest) => SettingsUpdateRequest) => {
    setDraft((current) => (current ? updater(current) : current));
    setSaveNotice(null);
    setImportNotice(null);
    setImportError(null);
    setSaveValidationError(null);
  };

  const saveCurrentCategory = () => {
    if (!draft) {
      return;
    }
    if (page === "providers") {
      const validationErrors = validateProviderCatalog(draft.providers);
      if (validationErrors.length > 0) {
        setSaveValidationError(validationErrors.join(" "));
        return;
      }
    }
    saveMutation.mutate(draft);
  };

  const exportSettings = () => {
    if (!draft) {
      return;
    }
    try {
      const payload = buildSettingsExportPayload(draft);
      const blob = new Blob([`${JSON.stringify(payload, null, 2)}\n`], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = buildSettingsExportFilename(payload);
      link.click();
      URL.revokeObjectURL(url);
      setImportError(null);
      setImportNotice("現在の draft を settings JSON として export しました。configPath や updatedAt は含めません。");
    } catch (error) {
      setImportNotice(null);
      setImportError(error instanceof Error ? formatSafeDisplayText(error.message) : "settings JSON を export できませんでした。");
    }
  };

  const importSettings = async (file: File | undefined) => {
    if (!file || !settingsQuery.data) {
      return;
    }
    try {
      const result = parseSettingsImportPayload(JSON.parse(await file.text()), settingsQuery.data);
      setDraft(result.draft);
      setSaveNotice(null);
      setImportError(null);
      setImportNotice(
        result.versionAdjusted
          ? "settings JSON を draft に読み込みました。version は現在の保存済み設定に合わせています。内容を確認して保存してください。"
          : "settings JSON を draft に読み込みました。内容を確認して保存してください。",
      );
    } catch (error) {
      setImportNotice(null);
      setImportError(error instanceof Error ? formatSafeDisplayText(error.message) : "settings JSON を読み込めませんでした。");
    }
  };

  useEffect(() => {
    if (stationEditorMode === "create") {
      return;
    }
    if (!stationsQuery.data?.length) {
      return;
    }
    if (!selectedStationId || !stationsQuery.data.some((station) => station.id === selectedStationId)) {
      setSelectedStationId(stationsQuery.data[0].id);
    }
  }, [selectedStationId, setSelectedStationId, stationEditorMode, stationsQuery.data]);

  useEffect(() => {
    if (templateEditorMode === "create") {
      return;
    }
    if (!templatesQuery.data?.length) {
      setSelectedTemplateId(null);
      return;
    }
    if (selectedTemplateId && templatesQuery.data.some((template) => template.id === selectedTemplateId)) {
      return;
    }
    const preferredTemplate =
      (selectedStationId ? templatesQuery.data.find((template) => template.stationId === selectedStationId) : undefined) ??
      templatesQuery.data[0];
    setSelectedTemplateId(preferredTemplate.id);
  }, [selectedStationId, selectedTemplateId, templateEditorMode, templatesQuery.data]);

  useEffect(() => {
    setProgrammingDraft(stationProgrammingQuery.data ? createProgrammingDraft(stationProgrammingQuery.data) : null);
  }, [stationProgrammingQuery.data]);

  useEffect(() => {
    if (stationEditorMode === "create") {
      return;
    }
    setStationDraft(stationDetailQuery.data ? createStationDraft(stationDetailQuery.data) : null);
  }, [stationDetailQuery.data, stationEditorMode]);

  useEffect(() => {
    if (templateEditorMode === "create") {
      return;
    }
    setTemplateDraft(templateDetailQuery.data ? createProgramTemplateDraftFromDetail(templateDetailQuery.data) : null);
  }, [templateDetailQuery.data, templateEditorMode]);

  useEffect(() => {
    setProgrammingNotice(null);
  }, [selectedStationId]);

  const startBlankStationDraft = () => {
    if (!confirmDiscardStationChanges("新規 station draft の開始")) {
      return;
    }
    const nextDraft = createBlankStationDraft(stationsQuery.data ?? []);
    setStationEditorMode("create");
    setStationCreateBaseDraft(cloneStationDraft(nextDraft));
    setStationCreateSourceId(selectedStationId);
    setStationDraft(nextDraft);
    setSelectedStationId(null);
    setProgrammingDraft(null);
    setStationNotice("新規 station draft を作成しました。保存後に programming policy を設定できます。");
    stationMutation.reset();
    createStationMutation.reset();
  };

  const startDuplicatedStationDraft = () => {
    if (!stationDetailQuery.data) {
      return;
    }
    if (!confirmDiscardStationChanges("station 複製 draft の開始")) {
      return;
    }
    const nextDraft = createDuplicatedStationDraft(stationDetailQuery.data, stationsQuery.data ?? [], templatesQuery.data ?? []);
    setStationEditorMode("create");
    setStationCreateBaseDraft(cloneStationDraft(nextDraft));
    setStationCreateSourceId(selectedStationId);
    setStationDraft(nextDraft);
    setSelectedStationId(null);
    setProgrammingDraft(null);
    setStationNotice(
      "選択中の station から新規 draft を作成しました。ID と周波数は新規値へ調整し、station 固有 template 参照は引き継ぎません。",
    );
    stationMutation.reset();
    createStationMutation.reset();
  };

  const cancelStationDraft = () => {
    if (!confirmDiscardStationChanges("station draft のクローズ")) {
      return;
    }
    setStationEditorMode("existing");
    setStationCreateBaseDraft(null);
    setStationDraft(null);
    setProgrammingDraft(null);
    createStationMutation.reset();
    stationMutation.reset();
    setStationNotice("新規 station draft を閉じました。");
    const fallbackStationId =
      stationCreateSourceId && stationsQuery.data?.some((station) => station.id === stationCreateSourceId)
        ? stationCreateSourceId
        : (stationsQuery.data?.[0]?.id ?? null);
    setSelectedStationId(fallbackStationId);
    setStationCreateSourceId(null);
  };

  const startBlankTemplateDraft = () => {
    const nextDraft = createBlankProgramTemplateDraft(selectedStationId);
    setTemplateEditorMode("create");
    setTemplateCreateBaseDraft(cloneProgramTemplateDraft(nextDraft));
    setTemplateCreateSourceId(selectedTemplateId);
    setTemplateDraft(nextDraft);
    setSelectedTemplateId(null);
    setTemplateNotice(
      selectedStationId
        ? "新規 template draft を作成しました。選択中 station に紐づく STATION scope で開始しています。"
        : "新規 template draft を作成しました。GLOBAL scope から始めています。",
    );
    createTemplateMutation.reset();
    templateMutation.reset();
  };

  const startDuplicatedTemplateDraft = () => {
    if (!templateDetailQuery.data) {
      return;
    }
    const nextDraft = createDuplicatedProgramTemplateDraft(templateDetailQuery.data, templatesQuery.data ?? []);
    setTemplateEditorMode("create");
    setTemplateCreateBaseDraft(cloneProgramTemplateDraft(nextDraft));
    setTemplateCreateSourceId(selectedTemplateId);
    setTemplateDraft(nextDraft);
    setSelectedTemplateId(null);
    setTemplateNotice("選択中 template を複製した新規 draft を作成しました。slot 構成と policy は保持し、template ID は新規候補へ補正しています。");
    createTemplateMutation.reset();
    templateMutation.reset();
  };

  const cancelTemplateDraft = () => {
    setTemplateEditorMode("existing");
    setTemplateCreateBaseDraft(null);
    setTemplateDraft(null);
    createTemplateMutation.reset();
    templateMutation.reset();
    setTemplateNotice("新規 template draft を閉じました。");
    const preferredTemplateId =
      templateCreateSourceId && templatesQuery.data?.some((template) => template.id === templateCreateSourceId)
        ? templateCreateSourceId
        : ((selectedStationId
            ? templatesQuery.data?.find((template) => template.stationId === selectedStationId)?.id
            : null) ??
          (templatesQuery.data?.[0]?.id ?? null));
    setSelectedTemplateId(preferredTemplateId);
    setTemplateCreateSourceId(null);
  };

  if (!hasAdminToken) {
    return (
      <PanelGrid>
        <PanelColumn className="xl:col-span-7">
          <Card>
            <SectionHeader
              eyebrow="Settings"
              title="管理者ログインが必要です"
              description="設定画面は管理者だけが利用できます。公開画面からは設定内容や管理操作を表示しません。"
            />
            <EmptyState
              title="設定画面は管理トークンが必要です"
              description="管理者としてログインしてから開いてください。"
            />
          </Card>
        </PanelColumn>
      </PanelGrid>
    );
  }

  return (
    <PanelGrid>
      <PanelColumn className={page === "providers" || page === "programming" ? "xl:col-span-7" : "xl:col-span-12"}>
        {page === "system" || page === "providers" || page === "playout" ? (
        <Card>
          <SectionHeader
            eyebrow={SETTINGS_PAGE_COPY[page].eyebrow}
            title={SETTINGS_PAGE_COPY[page].title}
            description={SETTINGS_PAGE_COPY[page].description}
            action={
              <div className="flex flex-wrap items-center gap-2">
                {page === "providers" ? (
                  <Button tone="secondary" onClick={() => connectionsMutation.mutate()} disabled={connectionsMutation.isPending}>
                    {connectionsMutation.isPending ? "接続を確認中..." : "保存済み設定で接続を確認"}
                  </Button>
                ) : null}
                <Button tone="ghost" onClick={resetDraft} disabled={!isDirty || saveMutation.isPending}>
                  未保存の変更を破棄
                </Button>
                <Button tone="primary" onClick={saveCurrentCategory} disabled={!draft || !isDirty || saveMutation.isPending}>
                  {saveMutation.isPending ? "保存中..." : "このカテゴリーの変更を保存"}
                </Button>
              </div>
            }
          />

          {settingsQuery.data && draft ? (
            <div className="space-y-4">
              <div className="grid gap-3 md:grid-cols-4">
                <Metric label="設定バージョン" value={settingsQuery.data.version} />
                <Metric label="形式バージョン" value={settingsQuery.data.schemaVersion} />
                <Metric label="待受先" value={`${draft.server.bindHost}:${draft.server.port}`} tone={bindHostWarning ? "warning" : "default"} />
                <Metric label="最終更新" value={formatTimestamp(settingsQuery.data.updatedAt)} />
              </div>
              <Metric label="設定ファイル" value={settingsQuery.data.configPath} />
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={isDirty ? "warning" : "success"}>{isDirty ? "未保存の変更があります" : "保存済みです"}</Badge>
                {page === "playout" ? <Badge tone="accent">再生・編成の変更は次の番組から反映</Badge> : null}
              </div>

              {saveNotice ? <InlineNotice tone="accent" message={saveNotice} /> : null}
              {importNotice ? <InlineNotice tone="accent" message={importNotice} /> : null}
              {importError ? <InlineNotice tone="danger" message={importError} /> : null}
              {saveValidationError ? <InlineNotice tone="danger" message={saveValidationError} /> : null}
              {saveMutation.error instanceof Error ? (
                <InlineNotice tone="danger" message={getSettingsSaveErrorMessage(saveMutation.error, page)} />
              ) : null}
              {bindHostWarning ? (
                <InlineNotice
                  tone="warning"
                  message="`bindHost` が localhost 以外です。LAN へ公開される可能性があるため、管理トークン設定とネットワーク制御を併せて確認してください。"
                />
              ) : null}

              {page === "system" ? (
              <SettingsSection
                title="Server と保存先"
                description="Server を待ち受けるアドレスとポート、生成物とローカル音源を保存する場所を指定します。通常は待受アドレスを 127.0.0.1 のまま使用してください。"
              >
                <div className="grid gap-4 md:grid-cols-2">
                  <TextField
                    id="bindHost"
                    label="待受アドレス"
                    value={draft.server.bindHost}
                    onChange={(value) => updateDraft((current) => ({ ...current, server: { ...current.server, bindHost: value } }))}
                  />
                  <NumberField
                    id="serverPort"
                    label="待受ポート"
                    value={draft.server.port}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, server: { ...current.server, port: value } }))}
                  />
                  <TextField
                    id="dataRoot"
                    label="データ保存先"
                    value={draft.paths.dataRoot}
                    onChange={(value) => updateDraft((current) => ({ ...current, paths: { ...current.paths, dataRoot: value } }))}
                  />
                  <TextField
                    id="musicLibrary"
                    label="ローカル音源フォルダー"
                    value={draft.paths.musicLibrary}
                    onChange={(value) => updateDraft((current) => ({ ...current, paths: { ...current.paths, musicLibrary: value } }))}
                  />
                </div>
              </SettingsSection>
              ) : null}

              {page === "playout" ? (
              <SettingsSection
                title="キューと先読み"
                description="放送を途切れさせないため、再生可能な項目を何件・何分先まで準備するかを指定します。値を増やすと安定しやすくなりますが、生成処理と保存容量の負荷も増えます。"
              >
                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                  <NumberField
                    id="targetReadyCount"
                    label="目標の再生準備済み件数"
                    value={draft.playout.targetReadyCount}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, targetReadyCount: value } }))}
                  />
                  <NumberField
                    id="minimumReadyCount"
                    label="最低限の再生準備済み件数"
                    value={draft.playout.minimumReadyCount}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, minimumReadyCount: value } }))}
                  />
                  <NumberField
                    id="minReadyDurationMs"
                    label="最低限準備する再生時間（ミリ秒）"
                    value={draft.playout.minReadyDurationMs}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, minReadyDurationMs: value } }))}
                  />
                  <NumberField
                    id="maxPreparedDurationMs"
                    label="準備してよい最大時間（ミリ秒）"
                    value={draft.playout.maxPreparedDurationMs}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, maxPreparedDurationMs: value } }))}
                  />
                  <NumberField
                    id="maxPreparedBlocks"
                    label="準備してよい最大番組数"
                    value={draft.playout.maxPreparedBlocks}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, maxPreparedBlocks: value } }))}
                  />
                  <NumberField
                    id="scriptAheadCount"
                    label="先に生成する台本数"
                    value={draft.playout.scriptAheadCount}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, scriptAheadCount: value } }))}
                  />
                  <NumberField
                    id="ttsAheadCount"
                    label="先に生成する音声数"
                    value={draft.playout.ttsAheadCount}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, ttsAheadCount: value } }))}
                  />
                  <NumberField
                    id="musicAheadCount"
                    label="先に生成する楽曲数"
                    value={draft.playout.musicAheadCount}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, playout: { ...current.playout, musicAheadCount: value } }))}
                  />
                  <CheckboxField
                    id="idlePrefetchEnabled"
                    label="停止中も先読みする"
                    checked={draft.playout.idlePrefetchEnabled}
                    description="放送を開始していない時も、次に必要な台本・音声・楽曲を準備します。"
                    onChange={(checked) => updateDraft((current) => ({ ...current, playout: { ...current.playout, idlePrefetchEnabled: checked } }))}
                  />
                </div>
              </SettingsSection>
              ) : null}

              {page === "playout" ? (
              <SettingsSection title="生成物のキャッシュ" description="生成済みの台本、音声、音楽を保持する上限、保存日数、再利用してよい範囲を指定します。容量を抑える場合は保持上限と保存日数を小さくしてください。">
                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                  <NumberField
                    id="scriptMaxBytes"
                    label="台本の最大保存容量（バイト）"
                    value={draft.cache.scriptMaxBytes}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, scriptMaxBytes: value } }))}
                  />
                  <NumberField
                    id="ttsMaxBytes"
                    label="音声の最大保存容量（バイト）"
                    value={draft.cache.ttsMaxBytes}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, ttsMaxBytes: value } }))}
                  />
                  <NumberField
                    id="musicMaxBytes"
                    label="音楽の最大保存容量（バイト）"
                    value={draft.cache.musicMaxBytes}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, musicMaxBytes: value } }))}
                  />
                  <NumberField
                    id="scriptRetentionDays"
                    label="台本の保存日数"
                    value={draft.cache.scriptRetentionDays}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, scriptRetentionDays: value } }))}
                  />
                  <NumberField
                    id="ttsRetentionDays"
                    label="音声の保存日数"
                    value={draft.cache.ttsRetentionDays}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, ttsRetentionDays: value } }))}
                  />
                  <NumberField
                    id="musicRetentionDays"
                    label="音楽の保存日数"
                    value={draft.cache.musicRetentionDays}
                    min={0}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, musicRetentionDays: value } }))}
                  />
                  <SelectField
                    id="scriptReuseScope"
                    label="台本を再利用できる範囲"
                    value={draft.cache.scriptReuseScope}
                    options={REUSE_SCOPE_OPTIONS}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, scriptReuseScope: value } }))}
                  />
                  <SelectField
                    id="ttsReuseScope"
                    label="音声を再利用できる範囲"
                    value={draft.cache.ttsReuseScope}
                    options={REUSE_SCOPE_OPTIONS}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, ttsReuseScope: value } }))}
                  />
                  <SelectField
                    id="musicReuseScope"
                    label="音楽を再利用できる範囲"
                    value={draft.cache.musicReuseScope}
                    options={REUSE_SCOPE_OPTIONS}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, musicReuseScope: value } }))}
                  />
                  <NumberField
                    id="cleanupBatchSize"
                    label="1 回に削除する最大件数"
                    value={draft.cache.cleanupBatchSize}
                    min={1}
                    onChange={(value) => updateDraft((current) => ({ ...current, cache: { ...current.cache, cleanupBatchSize: value } }))}
                  />
                </div>
              </SettingsSection>
              ) : null}

              {page === "playout" ? (
              <SettingsSection
                title="全局共通の編成既定値"
                description="局ごとの編成ポリシーが未設定または解決できない場合に使う、計画時間と最終的な代替動作を指定します。通常の局別設定は「番組編成」で行います。"
              >
                <div className="grid gap-4 md:grid-cols-2">
                  <NumberField
                    id="defaultPlanningHorizonMinutes"
                    label="既定の編成計画時間（分）"
                    value={draft.programming.defaultPlanningHorizonMinutes}
                    min={1}
                    onChange={(value) =>
                      updateDraft((current) => ({
                        ...current,
                        programming: { ...current.programming, defaultPlanningHorizonMinutes: value },
                      }))
                    }
                  />
                  <TextField
                    id="seedImportRef"
                    label="初期編成データの参照先"
                    value={draft.programming.seedImportRef}
                    onChange={(value) => updateDraft((current) => ({ ...current, programming: { ...current.programming, seedImportRef: value } }))}
                  />
                  <CheckboxField
                    id="legacyRatioFallback"
                    label="固定比率の最終代替を許可する"
                    checked={draft.programming.legacyRatioFallback}
                    description="番組テンプレートを解決できない場合に、固定比率による従来方式で放送を継続します。"
                    onChange={(checked) => updateDraft((current) => ({ ...current, programming: { ...current.programming, legacyRatioFallback: checked } }))}
                  />
                </div>
              </SettingsSection>
              ) : null}

              {page === "providers" ? (
              <SettingsSection
                title="Provider の接続先と切替順"
                description="台本生成、音声合成、音楽生成ごとに接続先を指定します。SeedShiftRadio Server と Ollama / ACE-Step が同じ 192.168.0.30 上で動く標準構成では、Server から見た接続先として 127.0.0.1 を使用できます。"
              >
                <div className="space-y-4">
                  {PROVIDER_GROUPS.map((groupKey) => (
                    <ProviderGroupEditor
                      key={groupKey}
                      groupKey={groupKey}
                      group={draft.providers[groupKey]}
                      health={connectionsMutation.data?.providers[groupKey]}
                      hasUnsavedChanges={isDirty}
                      modelLoadResult={aceStepModelLoadMutation.data}
                      modelLoadError={aceStepModelLoadMutation.error}
                      loadingModelProfile={aceStepModelLoadMutation.variables}
                      isModelLoadPending={aceStepModelLoadMutation.isPending}
                      onLoadAceStepProfile={(providerKey, profileId) =>
                        aceStepModelLoadMutation.mutate({ providerKey, profileId })
                      }
                      onDefaultChange={(providerKey) =>
                        updateDraft((current) => ({
                          ...current,
                          providers: {
                            ...current.providers,
                            [groupKey]: {
                              ...current.providers[groupKey],
                              defaultProvider: providerKey,
                              fallbackProviders: current.providers[groupKey].fallbackProviders.filter((value) => value !== providerKey),
                            },
                          },
                        }))
                      }
                      onFallbackToggle={(providerKey, checked) =>
                        updateDraft((current) => ({
                          ...current,
                          providers: {
                            ...current.providers,
                            [groupKey]: {
                              ...current.providers[groupKey],
                              fallbackProviders: checked
                                ? [...current.providers[groupKey].fallbackProviders, providerKey]
                                    .filter((value, index, array) => array.indexOf(value) === index)
                                    .filter((value) => value !== current.providers[groupKey].defaultProvider)
                                : current.providers[groupKey].fallbackProviders.filter((value) => value !== providerKey),
                            },
                          },
                        }))
                      }
                      onEndpointChange={(providerKey, field, value) =>
                        updateDraft((current) => ({
                          ...current,
                          providers: {
                            ...current.providers,
                            [groupKey]: {
                              ...current.providers[groupKey],
                              providers: {
                                ...current.providers[groupKey].providers,
                                [providerKey]: {
                                  ...current.providers[groupKey].providers[providerKey],
                                  [field]: value,
                                },
                              },
                            },
                          },
                        }))
                      }
                    />
                  ))}
                </div>
              </SettingsSection>
              ) : null}

              {page === "system" ? (
              <SettingsSection title="管理認証と縮退配信" description="管理 API が参照するトークンの保管場所と、生成物が間に合わない場合に代替音声を配信するかを指定します。秘密値そのものは入力しないでください。">
                <div className="grid gap-4 md:grid-cols-2">
                  <TextField
                    id="adminTokenRef"
                    label="管理トークンの参照先"
                    value={draft.security.adminTokenRef ?? ""}
                    placeholder="env:SEEDSHIFT_ADMIN_TOKEN"
                    onChange={(value) =>
                      updateDraft((current) => ({
                        ...current,
                        security: { ...current.security, adminTokenRef: value.trim() ? value : null },
                      }))
                    }
                  />
                  <CheckboxField
                    id="placeholderEnabled"
                    label="代替音声の配信を許可する"
                    checked={draft.features.streaming.placeholderEnabled}
                    description="再生する生成物が見つからない場合に、無音停止を避けるための代替音声を返します。"
                    onChange={(checked) =>
                      updateDraft((current) => ({
                        ...current,
                        features: {
                          ...current.features,
                          streaming: { ...current.features.streaming, placeholderEnabled: checked },
                        },
                      }))
                    }
                  />
                </div>
              </SettingsSection>
              ) : null}

              {page === "system" ? (
              <SettingsSection
                title="設定ファイルの入出力"
                description="現在の入力内容を JSON ファイルへ書き出したり、JSON ファイルを未保存の入力内容として読み込んだりできます。読み込んだだけでは Server の設定は変わりません。内容を確認してから保存してください。"
              >
                <div className="space-y-3">
                  <div className="flex flex-wrap gap-2">
                    <Button type="button" tone="ghost" onClick={exportSettings} disabled={!draft}>
                      JSON を書き出す
                    </Button>
                    <Button type="button" tone="secondary" onClick={() => importInputRef.current?.click()} disabled={!settingsQuery.data}>
                      JSON を読み込む
                    </Button>
                    <input
                      ref={importInputRef}
                      type="file"
                      accept="application/json,.json"
                      className="hidden"
                    aria-label="設定 JSON を読み込む"
                      onChange={(event) => {
                        void importSettings(event.currentTarget.files?.[0]);
                        event.currentTarget.value = "";
                      }}
                    />
                  </div>
                  <InlineNotice
                    tone="warning"
                    message="JSON 内の更新日時と設定ファイルの場所は読み込みません。API キーと管理トークンには、env: で始まる環境変数参照または file: で始まるファイル参照だけを指定できます。"
                  />
                </div>
              </SettingsSection>
              ) : null}
            </div>
          ) : (
            <EmptyState
              title="設定を取得できません"
              description={settingsQuery.error instanceof Error ? formatSafeDisplayText(settingsQuery.error.message) : "管理トークンの設定を確認してください。"}
            />
          )}
        </Card>
        ) : null}

        {page === "stations" || page === "programming" ? (
        <StationOverviewCard
          mode={page === "stations" ? "station" : "programming"}
          stations={stationsQuery.data ?? []}
          isCreatingStation={stationEditorMode === "create"}
          selectedStationId={selectedStationId}
          station={stationDetailQuery.data}
          programming={stationProgrammingQuery.data}
          templates={templatesQuery.data ?? []}
          stationDraft={stationDraft}
          stationDirty={isStationDirty}
          stationSaving={stationMutation.isPending || createStationMutation.isPending}
          stationNotice={stationNotice}
          stationError={createStationMutation.error ?? stationMutation.error}
          programmingDraft={programmingDraft}
          programmingDirty={isProgrammingDirty}
          programmingSaving={programmingMutation.isPending}
          programmingNotice={programmingNotice}
          programmingError={programmingMutation.error}
          onSelectStation={(stationId) => {
            if (!stationId) {
              if (stationEditorMode !== "create") {
                startBlankStationDraft();
              }
              return;
            }
            if (stationId !== selectedStationId && !confirmDiscardStationChanges("station の切り替え")) {
              return;
            }
            setStationEditorMode("existing");
            setStationCreateBaseDraft(null);
            setStationCreateSourceId(null);
            setSelectedStationId(stationId);
            createStationMutation.reset();
            stationMutation.reset();
          }}
          onStartCreateStation={startBlankStationDraft}
          onDuplicateStation={startDuplicatedStationDraft}
          onCancelCreateStation={cancelStationDraft}
          onStationDraftChange={setStationDraft}
          onResetStation={() => {
            if (stationEditorMode === "create" && stationCreateBaseDraft) {
              setStationDraft(cloneStationDraft(stationCreateBaseDraft));
              setStationNotice("新規 station draft の変更を破棄しました。");
            } else if (stationDetailQuery.data) {
              setStationDraft(createStationDraft(stationDetailQuery.data));
              setStationNotice("未保存の局基本情報変更を破棄しました。");
            }
          }}
          onSaveStation={() => {
            if (stationEditorMode === "create" && stationDraft) {
              createStationMutation.mutate(stationDraft);
            } else if (selectedStationId && stationDraft) {
              stationMutation.mutate({ stationId: selectedStationId, body: stationDraft });
            }
          }}
          onProgrammingDraftChange={setProgrammingDraft}
          onResetProgramming={() => {
            if (stationProgrammingQuery.data) {
              setProgrammingDraft(createProgrammingDraft(stationProgrammingQuery.data));
              setProgrammingNotice("未保存の番組編成ポリシー変更を破棄しました。");
            }
          }}
          onSaveProgramming={() => {
            if (selectedStationId && programmingDraft) {
              programmingMutation.mutate({ stationId: selectedStationId, body: programmingDraft });
            }
          }}
        />
        ) : null}

        {page === "programming" ? (
        <ProgramTemplateCard
          stations={stationsQuery.data ?? []}
          templates={templatesQuery.data ?? []}
          isCreatingTemplate={templateEditorMode === "create"}
          selectedTemplateId={selectedTemplateId}
          template={templateDetailQuery.data}
          draft={templateDraft}
          isDirty={isTemplateDirty}
          isSaving={templateMutation.isPending || createTemplateMutation.isPending}
          notice={templateNotice}
          error={createTemplateMutation.error ?? templateMutation.error}
          onSelectTemplate={(templateId) => {
            if (!templateId) {
              if (templateEditorMode !== "create") {
                startBlankTemplateDraft();
              }
              return;
            }
            setTemplateEditorMode("existing");
            setTemplateCreateBaseDraft(null);
            setTemplateCreateSourceId(null);
            setSelectedTemplateId(templateId);
            createTemplateMutation.reset();
            templateMutation.reset();
          }}
          onStartCreateTemplate={startBlankTemplateDraft}
          onDuplicateTemplate={startDuplicatedTemplateDraft}
          onCancelCreateTemplate={cancelTemplateDraft}
          onDraftChange={setTemplateDraft}
          onReset={() => {
            if (templateEditorMode === "create" && templateCreateBaseDraft) {
              setTemplateDraft(cloneProgramTemplateDraft(templateCreateBaseDraft));
              setTemplateNotice("新規 template draft の変更を破棄しました。");
            } else if (templateDetailQuery.data) {
              setTemplateDraft(createProgramTemplateDraftFromDetail(templateDetailQuery.data));
              setTemplateNotice("未保存の template 変更を破棄しました。");
            }
          }}
          onSave={() => {
            if (!templateDraft) {
              return;
            }
            const body = serializeProgramTemplateDraft(templateDraft);
            if (templateEditorMode === "create") {
              createTemplateMutation.mutate(body);
            } else if (selectedTemplateId) {
              templateMutation.mutate({ templateId: selectedTemplateId, body });
            }
          }}
        />
        ) : null}
      </PanelColumn>

      {page === "providers" || page === "programming" ? (
      <PanelColumn className="xl:col-span-5">
        {page === "providers" ? (
        <Card>
          <SectionHeader
            eyebrow="Connection Test"
            title="接続確認の結果"
            description="接続確認は保存済みの設定を使います。入力中の変更がある場合は、先に左側の保存ボタンで保存してください。"
          />
          <div className="space-y-3">
            <Metric label="確認日時" value={connectionsMutation.data?.checkedAt ?? "未実行"} tone={connectionsMutation.data ? "success" : "default"} />
            {connectionsMutation.data ? (
              <ConnectionResults response={connectionsMutation.data} />
            ) : (
              <EmptyState
                title="接続確認はまだ実行されていません"
                description={
                  connectionsMutation.error instanceof Error
                    ? formatSafeDisplayText(connectionsMutation.error.message)
                    : "上のボタンで保存済み設定の疎通確認を実行できます。"
                }
              />
            )}
          </div>
        </Card>
        ) : null}

        {page === "programming" ? (
        <ProgrammingPreviewCard
          stations={stationsQuery.data ?? []}
          selectedStationId={selectedStationId}
          previewDraft={previewDraft}
          previewResult={previewMutation.data}
          previewError={previewMutation.error}
          previewPending={previewMutation.isPending}
          previewUsesPolicyDraft={previewUsesPolicyDraft}
          previewTemplateDraftId={previewTemplateDraftId}
          onStationChange={setSelectedStationId}
          onPreviewDraftChange={setPreviewDraft}
          onPreview={() => previewMutation.mutate()}
        />
        ) : null}
      </PanelColumn>
      ) : null}
    </PanelGrid>
  );
}

function SettingsSection({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return (
    <Card className="p-4">
      <div className="mb-4 space-y-1">
        <h3 className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500">{title}</h3>
        <p className="text-sm leading-6 text-slate-600">{description}</p>
      </div>
      {children}
    </Card>
  );
}

function InlineNotice({ tone, message }: { tone: "accent" | "warning" | "danger"; message: string }) {
  const toneClass = {
    accent: "border-teal-200 bg-teal-50 text-teal-900",
    warning: "border-amber-200 bg-amber-50 text-amber-900",
    danger: "border-rose-200 bg-rose-50 text-rose-900",
  }[tone];
  return <div className={`rounded-2xl border px-4 py-3 text-sm leading-6 ${toneClass}`}>{message}</div>;
}

function TextField({
  id,
  label,
  value,
  placeholder,
  description,
  suggestions,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  placeholder?: string;
  description?: string;
  suggestions?: readonly string[];
  onChange: (value: string) => void;
}) {
  const listId = suggestions?.length ? `${id}-suggestions` : undefined;
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        list={listId}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.currentTarget.value)}
      />
      {listId ? (
        <datalist id={listId}>
          {suggestions?.map((suggestion) => <option key={suggestion} value={suggestion} />)}
        </datalist>
      ) : null}
      {description ? <p className="mt-1 text-xs leading-5 text-slate-500">{description}</p> : null}
    </div>
  );
}

function NumberField({
  id,
  label,
  value,
  min,
  step,
  description,
  onChange,
}: {
  id: string;
  label: string;
  value: number;
  min: number;
  step?: number;
  description?: string;
  onChange: (value: number) => void;
}) {
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        type="number"
        min={min}
        step={step}
        value={value}
        onChange={(event) => onChange(Number.isNaN(event.currentTarget.valueAsNumber) ? min : event.currentTarget.valueAsNumber)}
      />
      {description ? <p className="mt-2 text-xs leading-5 text-amber-700">{description}</p> : null}
    </div>
  );
}

function SelectField({
  id,
  label,
  value,
  options,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  options: readonly string[];
  onChange: (value: string) => void;
}) {
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <select id={id} className={SELECT_CLASS_NAME} value={value} onChange={(event) => onChange(event.currentTarget.value)}>
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    </div>
  );
}

function CheckboxField({
  id,
  label,
  checked,
  description,
  onChange,
}: {
  id: string;
  label: string;
  checked: boolean;
  description?: string;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label htmlFor={id} className="flex min-h-[84px] items-start gap-3 rounded-2xl border border-slate-200 bg-white/70 px-4 py-3">
      <input id={id} type="checkbox" checked={checked} onChange={(event) => onChange(event.currentTarget.checked)} className="mt-1 h-4 w-4 accent-teal-600" />
      <span>
        <span className="block text-sm font-semibold text-slate-950">{label}</span>
        {description ? <span className="mt-1 block text-sm leading-6 text-slate-600">{description}</span> : null}
      </span>
    </label>
  );
}

function CheckboxGroup<T extends string>({
  title,
  idPrefix,
  options,
  selected,
  onChange,
}: {
  title: string;
  idPrefix: string;
  options: readonly T[];
  selected: readonly string[];
  onChange: (value: T, checked: boolean) => void;
}) {
  return (
    <div className="mt-4 space-y-2">
      <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">{title}</div>
      <div className="flex flex-wrap gap-2">
        {options.map((option) => (
          <label key={option} htmlFor={`${idPrefix}-${option}`} className="inline-flex items-center gap-2 rounded-2xl border border-slate-200 bg-white/70 px-3 py-2 text-sm">
            <input
              id={`${idPrefix}-${option}`}
              type="checkbox"
              checked={selected.includes(option)}
              onChange={(event) => onChange(option, event.currentTarget.checked)}
              className="h-4 w-4 accent-teal-600"
            />
            <span>{option}</span>
          </label>
        ))}
      </div>
    </div>
  );
}

function SegmentCheckboxes({
  idPrefix,
  selected,
  onChange,
}: {
  idPrefix: string;
  selected: readonly string[];
  onChange: (value: (typeof SEGMENT_TYPE_OPTIONS)[number], checked: boolean) => void;
}) {
  return <CheckboxGroup title="この構成枠で使用できるセグメント" idPrefix={idPrefix} options={SEGMENT_TYPE_OPTIONS} selected={selected} onChange={onChange} />;
}

function ProviderGroupEditor({
  groupKey,
  group,
  health,
  hasUnsavedChanges,
  modelLoadResult,
  modelLoadError,
  loadingModelProfile,
  isModelLoadPending,
  onLoadAceStepProfile,
  onDefaultChange,
  onFallbackToggle,
  onEndpointChange,
}: {
  groupKey: keyof ProviderCatalog;
  group: ProviderGroup;
  health?: ProviderHealthPayload;
  hasUnsavedChanges: boolean;
  modelLoadResult?: AceStepModelLoadResponse;
  modelLoadError: Error | null;
  loadingModelProfile?: { providerKey: string; profileId: string };
  isModelLoadPending: boolean;
  onLoadAceStepProfile: (providerKey: string, profileId: string) => void;
  onDefaultChange: (providerKey: string) => void;
  onFallbackToggle: (providerKey: string, checked: boolean) => void;
  onEndpointChange: (providerKey: string, field: ProviderEndpointField, value: ProviderEndpointValue) => void;
}) {
  const providerKeys = Object.keys(group.providers);

  return (
    <Card tone="accent" className="p-4">
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div className="text-sm font-semibold text-slate-950">{PROVIDER_LABELS[groupKey]}</div>
        <Badge tone="accent">{group.defaultProvider}</Badge>
        {health ? <Badge tone={health.status === "UP" ? "success" : health.status === "DEGRADED" ? "warning" : "danger"}>{health.status}</Badge> : null}
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <div>
          <Label htmlFor={`${groupKey}-default`}>最初に使用する Provider</Label>
          <select
            id={`${groupKey}-default`}
            className={SELECT_CLASS_NAME}
            value={group.defaultProvider}
            onChange={(event) => onDefaultChange(event.currentTarget.value)}
          >
            {providerKeys.map((providerKey) => (
              <option key={providerKey} value={providerKey}>
                {providerKey}
              </option>
            ))}
          </select>
        </div>

        <div>
          <Label>障害時に切り替える Provider</Label>
          <div className="space-y-2">
            {providerKeys.filter((providerKey) => providerKey !== group.defaultProvider).length > 0 ? (
              providerKeys
                .filter((providerKey) => providerKey !== group.defaultProvider)
                .map((providerKey) => (
                  <label key={providerKey} className="flex items-center gap-2 rounded-2xl border border-slate-200 bg-white/70 px-4 py-3 text-sm text-slate-700">
                    <input
                      type="checkbox"
                      checked={group.fallbackProviders.includes(providerKey)}
                      onChange={(event) => onFallbackToggle(providerKey, event.currentTarget.checked)}
                      className="h-4 w-4 accent-teal-600"
                    />
                    <span>{providerKey}</span>
                  </label>
                ))
            ) : (
              <div className="rounded-2xl border border-dashed border-slate-300 bg-white/60 px-4 py-3 text-sm text-slate-500">
                切替候補にできる別の Provider は登録されていません。
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="mt-4 space-y-3">
        {providerKeys.map((providerKey) => {
          const endpoint = group.providers[providerKey];
          const profileEntries = Object.entries(endpoint.modelProfiles ?? {});
          const sameHostPreset = getSameHostProviderPreset(groupKey, providerKey, endpoint);
          const detectedModels = getDetectedProviderModels(health, providerKey);
          const isAceStep =
            groupKey === "musicGen"
            && (endpoint.adapter === "ACE_STEP" || endpoint.capabilities.includes("ACE_STEP"));
          const resultForProvider = modelLoadResult?.providerKey === providerKey ? modelLoadResult : undefined;
          const errorForProvider = loadingModelProfile?.providerKey === providerKey ? modelLoadError : null;
          return (
            <div key={providerKey} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-4">
              <div className="flex flex-wrap items-center gap-2">
                <div className="font-semibold text-slate-950">{providerKey}</div>
                {providerKey === group.defaultProvider ? <Badge tone="success">最優先</Badge> : null}
                {group.fallbackProviders.includes(providerKey) ? <Badge tone="warning">障害時に使用</Badge> : null}
                {groupKey === "musicGen" ? <Badge tone="accent">{endpoint.adapter ?? "MUSICGEN_WORKER"}</Badge> : null}
                {sameHostPreset ? (
                  <Button
                    type="button"
                    tone="ghost"
                    onClick={() => {
                      onEndpointChange(providerKey, "baseUrl", sameHostPreset.baseUrl);
                      onEndpointChange(providerKey, "healthPath", sameHostPreset.healthPath);
                      onEndpointChange(providerKey, "adapter", sameHostPreset.adapter);
                      if (!endpoint.defaultModelProfileId && sameHostPreset.defaultModelProfileId) {
                        onEndpointChange(providerKey, "defaultModelProfileId", sameHostPreset.defaultModelProfileId);
                      }
                    }}
                  >
                    同一サーバーの既定値を入力
                  </Button>
                ) : null}
              </div>
              <div className="mt-3 grid gap-4 md:grid-cols-2">
                <TextField
                  id={`${groupKey}-${providerKey}-baseUrl`}
                  label="接続先 URL"
                  value={endpoint.baseUrl}
                  onChange={(value) => onEndpointChange(providerKey, "baseUrl", value)}
                />
                <TextField
                  id={`${groupKey}-${providerKey}-healthPath`}
                  label="死活確認パス"
                  value={endpoint.healthPath}
                  onChange={(value) => onEndpointChange(providerKey, "healthPath", value)}
                />
                <NumberField
                  id={`${groupKey}-${providerKey}-timeoutMs`}
                  label="タイムアウト（ミリ秒）"
                  value={endpoint.timeoutMs}
                  min={100}
                  description={
                    groupKey === "llm" && endpoint.timeoutMs < 30_000
                      ? "接続確認には成功しても、モデルのコールドスタート中に実生成が失敗する短い設定です。実測ロード時間を上回る値（目安 30,000 ms 以上）を設定してください。"
                      : undefined
                  }
                  onChange={(value) => onEndpointChange(providerKey, "timeoutMs", value)}
                />
                <TextField
                  id={`${groupKey}-${providerKey}-capabilities`}
                  label="利用できる機能"
                  value={endpoint.capabilities.join(", ")}
                  placeholder="MUSIC_GEN, ACE_STEP, JAPANESE_LYRICS"
                  onChange={(value) => onEndpointChange(providerKey, "capabilities", splitCsv(value))}
                />
                {groupKey === "llm" ? (
                  <>
                    <SelectField
                      id={`${groupKey}-${providerKey}-adapter`}
                      label="LLM 接続方式"
                      value={endpoint.adapter ?? inferLlmAdapter(providerKey, endpoint)}
                      options={LLM_PROVIDER_ADAPTER_OPTIONS}
                      onChange={(value) => onEndpointChange(providerKey, "adapter", value)}
                    />
                    <TextField
                      id={`${groupKey}-${providerKey}-defaultModel`}
                      label="台本生成に使うモデル名"
                      value={endpoint.defaultModelProfileId ?? ""}
                      placeholder="qwen3:8b"
                      suggestions={detectedModels}
                      description={
                        detectedModels.length > 0
                          ? "接続確認で検出したモデルから選択するか、Ollama に登録済みのモデル名を入力します。"
                          : "Ollama の `ollama list` に表示されるモデル名を指定します。接続確認後は検出候補から選べます。"
                      }
                      onChange={(value) => onEndpointChange(providerKey, "defaultModelProfileId", value.trim() ? value : null)}
                    />
                  </>
                ) : null}
                {groupKey === "musicGen" ? (
                  <>
                    <SelectField
                      id={`${groupKey}-${providerKey}-adapter`}
                      label="接続方式"
                      value={endpoint.adapter ?? "MUSICGEN_WORKER"}
                      options={PROVIDER_ADAPTER_OPTIONS}
                      onChange={(value) => onEndpointChange(providerKey, "adapter", value)}
                    />
                    <TextField
                      id={`${groupKey}-${providerKey}-apiKeyRef`}
                      label="API キーの参照先"
                      value={endpoint.apiKeyRef ?? ""}
                      placeholder="env:ACESTEP_API_KEY"
                      onChange={(value) => onEndpointChange(providerKey, "apiKeyRef", value.trim() ? value : null)}
                    />
                    <TextField
                      id={`${groupKey}-${providerKey}-defaultProfile`}
                      label="既定の生成プロファイル"
                      value={endpoint.defaultModelProfileId ?? ""}
                      placeholder="ace-ja-fast"
                      onChange={(value) => onEndpointChange(providerKey, "defaultModelProfileId", value.trim() ? value : null)}
                    />
                    {isAceStep ? (
                      <div className="rounded-2xl border border-amber-200 bg-amber-50/80 px-4 py-3 text-sm leading-6 text-amber-950 md:col-span-2">
                        <div className="font-semibold">ACE-Step のロード済みモデルを切り替える</div>
                        <div className="mt-1">
                          下の生成プロファイルから、接続確認でACE-Step側に検出されたモデルを明示的にロードできます。
                          この操作は保存済み設定を使い、実行中の生成へ影響する可能性があります。
                        </div>
                        {hasUnsavedChanges ? (
                          <div className="mt-2 font-semibold">未保存の変更があります。先にこのカテゴリーの変更を保存してください。</div>
                        ) : detectedModels.length === 0 ? (
                          <div className="mt-2 font-semibold">先に「保存済み設定で接続を確認」を実行してモデル一覧を取得してください。</div>
                        ) : (
                          <div className="mt-2 text-xs">ACE-Step検出モデル: {detectedModels.join(", ")}</div>
                        )}
                        {resultForProvider ? (
                          <div className="mt-2 rounded-xl bg-white/80 px-3 py-2" role="status">
                            ロード完了: {resultForProvider.loadedModel ?? resultForProvider.profileId}
                            {resultForProvider.loadedLmModel ? ` / ${resultForProvider.loadedLmModel}` : ""}
                            {`（slot ${resultForProvider.slot}）`}
                          </div>
                        ) : null}
                        {errorForProvider ? (
                          <div className="mt-2 rounded-xl bg-red-50 px-3 py-2 text-red-800" role="alert">
                            {formatSafeDisplayText(errorForProvider.message)}
                          </div>
                        ) : null}
                      </div>
                    ) : null}
                    <div className="rounded-2xl border border-slate-200 bg-white/70 px-4 py-3 text-sm leading-6 text-slate-600 md:col-span-2">
                      <div className="mb-2 text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">利用可能な生成プロファイル</div>
                      {profileEntries.length > 0 ? (
                        <div className="grid gap-2 lg:grid-cols-2">
                          {profileEntries.map(([profileId, profile]) => {
                            const modelDetected = detectedModels.some((model) => aceStepModelMatches(model, profile.model));
                            const isThisProfileLoading =
                              isModelLoadPending
                              && loadingModelProfile?.providerKey === providerKey
                              && loadingModelProfile.profileId === profileId;
                            return (
                              <div key={profileId} className="rounded-2xl border border-slate-200 bg-white px-3 py-2">
                                <div className="font-semibold text-slate-950">{profileId}</div>
                                <div className="mt-1 text-xs text-slate-500">
                                  {profile.model} / {profile.lmModel}
                                </div>
                                <div className="mt-2 flex flex-wrap gap-2">
                                  <Badge tone="accent">{profile.lyricsLanguage}</Badge>
                                  <Badge tone="accent">{profile.lyricsTransliterationMode}</Badge>
                                  <Badge tone="accent">{profile.outputFormat}</Badge>
                                  <Badge tone={profile.thinking ? "success" : "warning"}>{profile.thinking ? "thinking" : "no thinking"}</Badge>
                                  <Badge tone="accent">max {profile.maxDurationSeconds}s</Badge>
                                  {isAceStep ? (
                                    <Badge tone={modelDetected ? "success" : "warning"}>
                                      {modelDetected ? "ACE-Step検出済み" : "ACE-Step未検出"}
                                    </Badge>
                                  ) : null}
                                </div>
                                {isAceStep ? (
                                  <Button
                                    type="button"
                                    tone="secondary"
                                    className="mt-3"
                                    disabled={hasUnsavedChanges || !modelDetected || isModelLoadPending}
                                    onClick={() => onLoadAceStepProfile(providerKey, profileId)}
                                  >
                                    {isThisProfileLoading ? "ACE-Stepでロード中..." : "このプロファイルをACE-Stepへロード"}
                                  </Button>
                                ) : null}
                              </div>
                            );
                          })}
                        </div>
                      ) : (
                        <div>保存済み設定に生成プロファイル情報がありません。</div>
                      )}
                    </div>
                  </>
                ) : null}
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}

function ConnectionResults({ response }: { response: ConnectionsTestResponse }) {
  return (
    <div className="space-y-3">
      {Object.entries(response.providers).map(([key, health]) => (
        <ConnectionResultCard key={key} label={key} health={health} />
      ))}
    </div>
  );
}

function ConnectionResultCard({ label, health }: { label: string; health: ProviderHealthPayload }) {
  const metadataEntries = getSafeMetadataEntries(health.metadata);

  return (
    <div className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="font-semibold text-slate-950">{label}</div>
        <Badge tone={health.status === "UP" ? "success" : health.status === "DEGRADED" ? "warning" : "danger"}>{health.status}</Badge>
      </div>
      <div className="mt-2 text-sm leading-6 text-slate-600">{formatSafeDisplayText(health.message)}</div>
      {health.baseUrl ? <div className="mt-1 break-all text-xs text-slate-500">接続先: {health.baseUrl}</div> : null}
      <div className="mt-3 grid gap-3 text-sm text-slate-500 md:grid-cols-2">
        <div>
          <div className="text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-400">Provider</div>
          <div className="mt-1">{health.providerKey ?? "-"}</div>
        </div>
        <div>
          <div className="text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-400">Response Time</div>
          <div className="mt-1">{health.responseTimeMs != null ? `${health.responseTimeMs} ms` : "-"}</div>
        </div>
      </div>
      {metadataEntries.length > 0 ? (
        <div className="mt-3 grid gap-2 text-sm text-slate-500 md:grid-cols-2">
          {metadataEntries.map((entry) => (
            <div key={entry.key} className="rounded-2xl border border-slate-200 bg-white/70 px-3 py-2">
              <div className="flex flex-wrap items-center gap-2">
                <div className="text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-400">{entry.key}</div>
                {entry.redacted ? <Badge tone="warning">redacted</Badge> : null}
              </div>
              <div className="mt-1 break-words">{entry.value}</div>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function createDraft(settings: SettingsResponse): SettingsUpdateRequest {
  return {
    version: settings.version,
    schemaVersion: settings.schemaVersion,
    server: {
      bindHost: settings.server.bindHost,
      port: settings.server.port,
    },
    paths: {
      dataRoot: settings.paths.dataRoot,
      musicLibrary: settings.paths.musicLibrary,
    },
    playout: {
      targetReadyCount: settings.playout.targetReadyCount,
      minimumReadyCount: settings.playout.minimumReadyCount,
      minReadyDurationMs: settings.playout.minReadyDurationMs,
      maxPreparedDurationMs: settings.playout.maxPreparedDurationMs,
      maxPreparedBlocks: settings.playout.maxPreparedBlocks,
      scriptAheadCount: settings.playout.scriptAheadCount,
      ttsAheadCount: settings.playout.ttsAheadCount,
      musicAheadCount: settings.playout.musicAheadCount,
      idlePrefetchEnabled: settings.playout.idlePrefetchEnabled,
    },
    cache: {
      scriptMaxBytes: settings.cache.scriptMaxBytes,
      ttsMaxBytes: settings.cache.ttsMaxBytes,
      musicMaxBytes: settings.cache.musicMaxBytes,
      scriptRetentionDays: settings.cache.scriptRetentionDays,
      ttsRetentionDays: settings.cache.ttsRetentionDays,
      musicRetentionDays: settings.cache.musicRetentionDays,
      scriptReuseScope: settings.cache.scriptReuseScope,
      ttsReuseScope: settings.cache.ttsReuseScope,
      musicReuseScope: settings.cache.musicReuseScope,
      cleanupBatchSize: settings.cache.cleanupBatchSize,
    },
    programming: {
      defaultPlanningHorizonMinutes: settings.programming.defaultPlanningHorizonMinutes,
      legacyRatioFallback: settings.programming.legacyRatioFallback,
      seedImportRef: settings.programming.seedImportRef,
    },
    providers: cloneProviders(settings.providers),
    security: {
      adminTokenRef: settings.security.adminTokenRef,
    },
    features: cloneFeatures(settings.features),
  };
}

function cloneProviders(providers: ProviderCatalog): ProviderCatalog {
  return {
    llm: cloneProviderGroup("llm", providers.llm),
    tts: cloneProviderGroup("tts", providers.tts),
    musicGen: cloneProviderGroup("musicGen", providers.musicGen),
  };
}

function cloneProviderGroup(groupKey: keyof ProviderCatalog, group: ProviderGroup): ProviderGroup {
  return {
    defaultProvider: group.defaultProvider,
    fallbackProviders: [...group.fallbackProviders],
    providers: Object.fromEntries(
      Object.entries(group.providers).map(([providerKey, endpoint]) => [
        providerKey,
        cloneProviderEndpoint(groupKey, providerKey, endpoint),
      ]),
    ),
  };
}

function cloneProviderEndpoint(groupKey: keyof ProviderCatalog, providerKey: string, endpoint: ProviderEndpoint): ProviderEndpoint {
  return {
    baseUrl: endpoint.baseUrl,
    healthPath: endpoint.healthPath,
    timeoutMs: endpoint.timeoutMs,
    capabilities: [...endpoint.capabilities],
    adapter: groupKey === "llm" ? (endpoint.adapter ?? inferLlmAdapter(providerKey, endpoint)) : endpoint.adapter,
    apiKeyRef: endpoint.apiKeyRef ?? null,
    defaultModelProfileId: endpoint.defaultModelProfileId ?? null,
    modelProfiles: Object.fromEntries(
      Object.entries(endpoint.modelProfiles ?? {}).map(([profileId, profile]) => [
        profileId,
        {
          model: profile.model,
          lmModel: profile.lmModel,
          thinking: profile.thinking,
          lyricsLanguage: profile.lyricsLanguage,
          lyricsTransliterationMode: profile.lyricsTransliterationMode,
          outputFormat: profile.outputFormat,
          maxDurationSeconds: profile.maxDurationSeconds,
        },
      ]),
    ),
  };
}

function cloneFeatures(features: FeatureSettings): FeatureSettings {
  return {
    streaming: {
      placeholderEnabled: features.streaming.placeholderEnabled,
    },
    jobExecution: {
      ...features.jobExecution,
      manual: { ...features.jobExecution.manual },
      automatic: { ...features.jobExecution.automatic },
    },
  };
}

function createStationDraft(station: StationDetail): StationUpdateRequest {
  return {
    version: station.version,
    id: station.id,
    name: station.name,
    frequencyMHz: station.frequencyMHz,
    genre: station.genre,
    languagePersonaId: station.languagePersonaId,
    defaultVoiceProfileId: station.defaultVoiceProfileId,
    isActive: station.isActive,
    programmingEnabled: station.programming.enabled,
    defaultProgramTemplateId: station.programming.defaultTemplateId,
  };
}

function createProgrammingDraft(programming: StationProgrammingResponse): StationProgrammingUpdateRequest {
  return {
    version: programming.version,
    enabled: programming.enabled,
    defaultTemplateId: programming.defaultTemplateId,
    fallbackStrategy: programming.fallbackStrategy,
    planningHorizonMinutes: programming.planningHorizonMinutes,
    preGeneration: { ...programming.preGeneration },
    replay: {
      ...programming.replay,
      eligibleSegmentTypes: [...programming.replay.eligibleSegmentTypes],
    },
    composition: {
      ...programming.composition,
      targetSegmentShares: { ...programming.composition.targetSegmentShares },
    },
    rules: programming.rules.map((rule) => ({
      priority: rule.priority,
      days: [...rule.days],
      startTime: rule.startTime,
      endTime: rule.endTime,
      minimumPendingLetters: rule.minimumPendingLetters,
      requiredProviderStates: [...rule.requiredProviderStates],
      templateId: rule.templateId,
    })),
  };
}

function splitCsv(value: string) {
  return value
    .split(",")
    .map((entry) => entry.trim())
    .filter((entry, index, array) => entry.length > 0 && array.indexOf(entry) === index);
}

function toggleStringList<T extends string>(values: readonly T[], value: T, checked: boolean): T[] {
  if (checked) {
    return values.includes(value) ? [...values] : [...values, value];
  }
  return values.filter((entry) => entry !== value);
}

function isTemplateUsableForStation(template: ProgramTemplateSummary, stationId: string) {
  return template.scope === "GLOBAL" || (template.scope === "STATION" && template.stationId === stationId);
}

function isTemplateUsableForDraft(template: ProgramTemplateSummary, draft: Pick<ProgramTemplateEditorDraft, "scope" | "stationId">) {
  if (draft.scope === "GLOBAL") {
    return template.scope === "GLOBAL";
  }
  return template.scope === "GLOBAL" || (template.scope === "STATION" && template.stationId === draft.stationId);
}

function suggestSlotId(slots: ProgramTemplateEditorDraft["slots"]) {
  const usedIds = new Set(slots.map((slot) => slot.slotId.trim()).filter(Boolean));
  if (!usedIds.has("slot")) {
    return "slot";
  }
  for (let index = 2; index <= 100; index += 1) {
    const candidate = `slot-${index}`;
    if (!usedIds.has(candidate)) {
      return candidate;
    }
  }
  return `slot-${Date.now()}`;
}

function getJsonObjectValidationMessage(value: string, label: string) {
  const trimmed = value.trim();
  if (!trimmed) {
    return null;
  }

  try {
    const parsed = JSON.parse(trimmed);
    if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
      return `${label} は JSON object で指定してください。`;
    }
    return null;
  } catch {
    return `${label} は JSON object で指定してください。`;
  }
}

function formatTimestamp(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat("ja-JP", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}

function isUnsafeBindHost(bindHost: string) {
  const normalized = bindHost.trim().toLowerCase();
  return normalized !== "127.0.0.1" && normalized !== "localhost" && normalized !== "::1";
}

type PreviewDraft = {
  at: string;
  pendingLetterCount: number;
  providerStates: ProgrammingPreviewRequest["providerStates"];
};

function StationOverviewCard({
  mode,
  stations,
  isCreatingStation,
  selectedStationId,
  station,
  programming,
  templates,
  stationDraft,
  stationDirty,
  stationSaving,
  stationNotice,
  stationError,
  programmingDraft,
  programmingDirty,
  programmingSaving,
  programmingNotice,
  programmingError,
  onSelectStation,
  onStartCreateStation,
  onDuplicateStation,
  onCancelCreateStation,
  onStationDraftChange,
  onResetStation,
  onSaveStation,
  onProgrammingDraftChange,
  onResetProgramming,
  onSaveProgramming,
}: {
  mode: "station" | "programming";
  stations: StationSummary[];
  isCreatingStation: boolean;
  selectedStationId: string | null;
  station?: StationDetail;
  programming?: StationProgrammingResponse;
  templates: ProgramTemplateSummary[];
  stationDraft: StationUpdateRequest | null;
  stationDirty: boolean;
  stationSaving: boolean;
  stationNotice: string | null;
  stationError: unknown;
  programmingDraft: StationProgrammingUpdateRequest | null;
  programmingDirty: boolean;
  programmingSaving: boolean;
  programmingNotice: string | null;
  programmingError: unknown;
  onSelectStation: (stationId: string | null) => void;
  onStartCreateStation: () => void;
  onDuplicateStation: () => void;
  onCancelCreateStation: () => void;
  onStationDraftChange: Dispatch<SetStateAction<StationUpdateRequest | null>>;
  onResetStation: () => void;
  onSaveStation: () => void;
  onProgrammingDraftChange: Dispatch<SetStateAction<StationProgrammingUpdateRequest | null>>;
  onResetProgramming: () => void;
  onSaveProgramming: () => void;
}) {
  const stationMetrics = stationDraft ?? (station ? createStationDraft(station) : null);

  return (
    <Card className="mt-4">
      <SectionHeader
        eyebrow={mode === "station" ? "Stations" : "Station Policy"}
        title={mode === "station" ? "局の管理" : "局ごとの番組編成ポリシー"}
        description={
          mode === "station"
            ? "放送局の基本情報を管理します。局名、周波数、番組で使う人格と音声、有効状態を確認して保存してください。"
            : "対象の局を選び、その局で使う番組テンプレート、先行生成、再放送、構成比率、時間帯ルールを管理します。"
        }
        action={
          mode === "station" ? (
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" tone="secondary" onClick={onStartCreateStation}>
              新しい局を作成
            </Button>
            <Button type="button" tone="ghost" onClick={onDuplicateStation} disabled={!station}>
              選択中の局を複製
            </Button>
            {isCreatingStation ? (
              <Button type="button" tone="ghost" onClick={onCancelCreateStation}>
                作成を中止
              </Button>
            ) : null}
          </div>
          ) : null
        }
      />
      <div className="space-y-4">
        <div>
          <Label htmlFor="settings-station-select">対象の局</Label>
          <select
            id="settings-station-select"
            className={SELECT_CLASS_NAME}
            value={isCreatingStation ? "" : (selectedStationId ?? "")}
            onChange={(event) => onSelectStation(event.currentTarget.value || null)}
          >
            {isCreatingStation ? <option value="">新しい局の入力内容</option> : null}
            {stations.map((entry) => (
              <option key={entry.id} value={entry.id}>
                {entry.name} ({entry.frequencyMHz} MHz)
              </option>
            ))}
          </select>
        </div>

        {!stations.length && !isCreatingStation ? (
          <EmptyState title="局がまだありません" description="「新しい局を作成」から最初の局を登録できます。" />
        ) : stationMetrics ? (
          <div className="space-y-4">
            {isCreatingStation ? (
              <InlineNotice
                tone="accent"
                message="新規局ドラフトを編集中です。保存後に一覧へ追加され、必要に応じて番組編成 policy を設定できます。"
              />
            ) : null}
            <div className="space-y-4">
              {mode === "station" ? (
              <>
              <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                <Metric label="ジャンル" value={stationMetrics.genre || "-"} />
                <Metric label="周波数" value={`${stationMetrics.frequencyMHz} MHz`} />
                <Metric label="人格" value={stationMetrics.languagePersonaId || "-"} />
                <Metric label="音声" value={stationMetrics.defaultVoiceProfileId || "-"} />
              </div>
              <StationBasicInfoEditor
                draft={stationDraft}
                mode={isCreatingStation ? "create" : "existing"}
                isDirty={stationDirty}
                isSaving={stationSaving}
                notice={stationNotice}
                error={stationError}
                onDraftChange={onStationDraftChange}
                onReset={onResetStation}
                onSave={onSaveStation}
              />
              </>
              ) : null}
              {mode === "programming" && isCreatingStation ? (
                <EmptyState
                  title="局を保存してから番組編成を設定します"
                  description="局の基本情報を「局」カテゴリーで保存した後、この画面で番組編成ポリシーを指定してください。"
                />
              ) : mode === "programming" && station ? (
                <>
                  <KeyValueGrid
                    title="現在の編成ポリシー"
                    entries={[
                      ["自動編成", programming?.enabled ? "有効" : "無効"],
                      ["既定テンプレート", programming?.defaultTemplateId ?? station.programming.defaultTemplateId ?? "未設定"],
                      ["代替方式", programming?.fallbackStrategy ?? station.programming.fallbackStrategy],
                      ["計画時間", `${programming?.planningHorizonMinutes ?? station.programming.planningHorizonMinutes} 分`],
                    ]}
                  />
                  <StationProgrammingPolicyEditor
                    stationId={station.id}
                    templates={templates}
                    draft={programmingDraft}
                    isDirty={programmingDirty}
                    isSaving={programmingSaving}
                    notice={programmingNotice}
                    error={programmingError}
                    onDraftChange={onProgrammingDraftChange}
                    onReset={onResetProgramming}
                    onSave={onSaveProgramming}
                  />
                  <div className="space-y-2">
                    <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">保存済みの時間帯ルール</div>
                    {programming?.rules.length ? (
                      programming.rules.map((rule) => (
                        <div key={rule.id} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3">
                          <div className="flex flex-wrap items-center gap-2">
                            <Badge tone="accent">優先度 {rule.priority}</Badge>
                            <Badge tone="default">{rule.templateId}</Badge>
                          </div>
                          <div className="mt-2 text-sm text-slate-600">
                            {rule.days.join(", ")} / {rule.startTime}-{rule.endTime} / 最低レター数 {rule.minimumPendingLetters}
                          </div>
                          <div className="mt-1 text-xs text-slate-500">
                            必要な Provider 状態: {rule.requiredProviderStates.length ? rule.requiredProviderStates.join(", ") : "指定なし"}
                          </div>
                        </div>
                      ))
                    ) : (
                      <EmptyState title="時間帯ルールはまだありません" description="ルールがない場合は、既定テンプレートと最終的な代替設定を使って放送を継続します。" />
                    )}
                  </div>
                </>
              ) : null}
              {mode === "station" ? (
              <>
              {isCreatingStation ? (
                <EmptyState
                  title="番組編成は局を保存した後に設定します"
                  description="局の作成と複製では基本情報だけを保存します。保存後に「番組編成」カテゴリーで既定テンプレートと時間帯ルールを指定してください。"
                />
              ) : null}
              </>
              ) : null}
            </div>
          </div>
        ) : (
          <EmptyState title="局詳細を取得できません" description="対象の局を選択するか、「新しい局を作成」から局の入力を始めてください。" />
        )}
      </div>
    </Card>
  );
}

function StationBasicInfoEditor({
  draft,
  mode,
  isDirty,
  isSaving,
  notice,
  error,
  onDraftChange,
  onReset,
  onSave,
}: {
  draft: StationUpdateRequest | null;
  mode: "existing" | "create";
  isDirty: boolean;
  isSaving: boolean;
  notice: string | null;
  error: unknown;
  onDraftChange: Dispatch<SetStateAction<StationUpdateRequest | null>>;
  onReset: () => void;
  onSave: () => void;
}) {
  if (!draft) {
    return <EmptyState title="局基本情報を読み込み中です" description="局の詳細を取得すると編集できます。" />;
  }

  const validationMessages = [
    draft.id.trim().length === 0 ? "Station ID は必須です。" : null,
    draft.name.trim().length === 0 ? "局名は必須です。" : null,
    draft.genre.trim().length === 0 ? "ジャンルは必須です。" : null,
    draft.languagePersonaId.trim().length === 0 ? "Persona ID は必須です。" : null,
    draft.defaultVoiceProfileId.trim().length === 0 ? "Voice Profile ID は必須です。" : null,
    draft.frequencyMHz < 0.1 ? "周波数は 0.1 MHz 以上で指定してください。" : null,
  ].filter((message): message is string => Boolean(message));

  const updateDraft = (updater: (current: StationUpdateRequest) => StationUpdateRequest) => {
    onDraftChange((current) => (current ? updater(current) : current));
  };

  return (
    <SettingsSection
      title="局の基本情報"
      description={
        mode === "create"
          ? "局 ID、局名、周波数、ジャンル、番組で使う人格と音声、有効状態を指定します。番組編成は局を作成した後に設定します。"
          : "局名、周波数、ジャンル、番組で使う人格と音声、有効状態を編集します。番組編成の有効化と既定テンプレートは「番組編成」で管理します。"
      }
    >
      <div className="space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={isDirty ? "warning" : "success"}>{isDirty ? "局に未保存の変更があります" : "局は保存済みです"}</Badge>
          <Badge tone="accent">{mode === "create" ? "新しい局" : `局バージョン ${draft.version}`}</Badge>
          {mode === "create" ? (
            <Badge tone="warning">番組編成は保存後に設定</Badge>
          ) : null}
          <Button type="button" tone="ghost" onClick={onReset} disabled={!isDirty || isSaving}>
            {mode === "create" ? "入力を初期状態へ戻す" : "未保存の変更を破棄"}
          </Button>
          <Button type="button" tone="primary" onClick={onSave} disabled={!isDirty || isSaving || validationMessages.length > 0}>
            {isSaving ? (mode === "create" ? "局を作成中..." : "局を保存中...") : mode === "create" ? "局を作成" : "局を保存"}
          </Button>
        </div>

        {notice ? <InlineNotice tone="accent" message={notice} /> : null}
        {error instanceof Error ? <InlineNotice tone="danger" message={formatSafeDisplayText(error.message)} /> : null}
        {validationMessages.map((message) => (
          <InlineNotice key={message} tone="warning" message={message} />
        ))}

        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <div>
            <Label htmlFor="station-id">局 ID</Label>
            <Input
              id="station-id"
              value={draft.id}
              readOnly={mode === "existing"}
              className={mode === "existing" ? "cursor-not-allowed bg-slate-100 text-slate-500" : undefined}
              onChange={(event) => {
                const value = event.currentTarget.value;
                updateDraft((current) => ({ ...current, id: value }));
              }}
            />
          </div>
          <TextField
            id="station-name"
            label="局名"
            value={draft.name}
            onChange={(value) => updateDraft((current) => ({ ...current, name: value }))}
          />
          <NumberField
            id="station-frequency"
            label="周波数（MHz）"
            value={draft.frequencyMHz}
            min={0.1}
            step={0.1}
            onChange={(value) => updateDraft((current) => ({ ...current, frequencyMHz: value }))}
          />
          <TextField
            id="station-genre"
            label="ジャンル"
            value={draft.genre}
            onChange={(value) => updateDraft((current) => ({ ...current, genre: value }))}
          />
          <TextField
            id="station-persona"
            label="人格 ID"
            value={draft.languagePersonaId}
            onChange={(value) => updateDraft((current) => ({ ...current, languagePersonaId: value }))}
          />
          <TextField
            id="station-voice"
            label="音声プロファイル ID"
            value={draft.defaultVoiceProfileId}
            onChange={(value) => updateDraft((current) => ({ ...current, defaultVoiceProfileId: value }))}
          />
          <CheckboxField
            id="station-active"
            label="この局を利用可能にする"
            checked={draft.isActive}
            description="無効にすると局一覧では非公開扱いにできます。削除は行いません。"
            onChange={(checked) => updateDraft((current) => ({ ...current, isActive: checked }))}
          />
        </div>
        <InlineNotice
          tone="warning"
          message={
            mode === "create"
              ? "Station ID は保存後に変更しません。Persona ID と Voice Profile ID は既存の登録 ID を指定し、ID 重複と周波数重複は Server 側でも検証されます。"
              : "Persona ID と Voice Profile ID は既存の登録 ID を指定してください。参照整合性と周波数重複は Server 側でも検証されます。"
          }
        />
      </div>
    </SettingsSection>
  );
}

function StationProgrammingPolicyEditor({
  stationId,
  templates,
  draft,
  isDirty,
  isSaving,
  notice,
  error,
  onDraftChange,
  onReset,
  onSave,
}: {
  stationId: string;
  templates: ProgramTemplateSummary[];
  draft: StationProgrammingUpdateRequest | null;
  isDirty: boolean;
  isSaving: boolean;
  notice: string | null;
  error: unknown;
  onDraftChange: Dispatch<SetStateAction<StationProgrammingUpdateRequest | null>>;
  onReset: () => void;
  onSave: () => void;
}) {
  if (!draft) {
    return <EmptyState title="番組編成ポリシーを読み込み中です" description="保存済みの編成設定を取得すると編集できます。" />;
  }

  const usableTemplates = templates.filter((template) => isTemplateUsableForStation(template, stationId));
  const shareTotal = SHARE_KEYS.reduce((total, key) => total + (draft.composition.targetSegmentShares[key] ?? 0), 0);
  const validationMessages = [
    draft.enabled && draft.rules.length === 0 ? "enabled=true の場合は rule が 1 件以上必要です。" : null,
    draft.planningHorizonMinutes < 1 ? "planningHorizonMinutes は 1 以上で指定してください。" : null,
    shareTotal !== 100 ? `targetSegmentShares の合計は 100 にしてください。現在は ${shareTotal} です。` : null,
    draft.replay.eligibleSegmentTypes.length === 0 ? "replay.eligibleSegmentTypes は 1 件以上必要です。" : null,
    draft.replay.maxReplaySharePercent > 100 ? "maxReplaySharePercent は 100 以下で指定してください。" : null,
    draft.rules.some((rule) => rule.days.length === 0) ? "各 rule には day が 1 件以上必要です。" : null,
    draft.rules.some((rule) => !rule.templateId) ? "各 rule には templateId が必要です。" : null,
    draft.replay.excludeLetterSegments && draft.replay.eligibleSegmentTypes.includes("LETTER")
      ? "excludeLetterSegments=true の場合、Replay 対象に LETTER は含められません。"
      : null,
  ].filter((message): message is string => Boolean(message));

  const updateDraft = (updater: (current: StationProgrammingUpdateRequest) => StationProgrammingUpdateRequest) => {
    onDraftChange((current) => (current ? updater(current) : current));
  };

  const updateRule = (index: number, updater: (rule: StationProgrammingUpdateRequest["rules"][number]) => StationProgrammingUpdateRequest["rules"][number]) => {
    updateDraft((current) => ({
      ...current,
      rules: current.rules.map((rule, ruleIndex) => (ruleIndex === index ? updater(rule) : rule)),
    }));
  };

  const addRule = () => {
    const templateId = draft.defaultTemplateId ?? usableTemplates[0]?.id;
    if (!templateId) {
      return;
    }
    updateDraft((current) => ({
      ...current,
      rules: [
        ...current.rules,
        {
          priority: 100,
          days: [...DAY_OPTIONS],
          startTime: "00:00",
          endTime: "23:59",
          minimumPendingLetters: 0,
          requiredProviderStates: [],
          templateId,
        },
      ],
    }));
  };

  const removeRule = (index: number) => {
    updateDraft((current) => ({
      ...current,
      rules: current.rules.filter((_, ruleIndex) => ruleIndex !== index),
    }));
  };

  return (
    <SettingsSection
      title="局別の番組編成"
      description="この局の既定テンプレート、先行生成、再放送、構成比率、時間帯ルールを編集します。保存した変更は、放送中の番組ではなく次の番組から反映されます。"
    >
      <div className="space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={isDirty ? "warning" : "success"}>{isDirty ? "編成に未保存の変更があります" : "編成は保存済みです"}</Badge>
          <Badge tone="accent">現在のバージョン {draft.version}</Badge>
          <Button type="button" tone="ghost" onClick={onReset} disabled={!isDirty || isSaving}>
            未保存の変更を破棄
          </Button>
          <Button type="button" tone="primary" onClick={onSave} disabled={!isDirty || isSaving || validationMessages.length > 0}>
            {isSaving ? "編成を保存中..." : "番組編成を保存"}
          </Button>
        </div>

        {notice ? <InlineNotice tone="accent" message={notice} /> : null}
        {error instanceof Error ? <InlineNotice tone="danger" message={formatSafeDisplayText(error.message)} /> : null}
        {validationMessages.map((message) => (
          <InlineNotice key={message} tone="warning" message={message} />
        ))}

        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <CheckboxField
            id="programming-enabled"
            label="この局で自動編成を使用する"
            checked={draft.enabled}
            description="無効にした場合は、局別テンプレートではなく固定比率の代替方式を中心に放送します。"
            onChange={(checked) => updateDraft((current) => ({ ...current, enabled: checked }))}
          />
          <div>
            <Label htmlFor="programming-default-template">Default Template</Label>
            <select
              id="programming-default-template"
              className={SELECT_CLASS_NAME}
              value={draft.defaultTemplateId ?? ""}
              onChange={(event) => {
                const value = event.currentTarget.value || null;
                updateDraft((current) => ({ ...current, defaultTemplateId: value }));
              }}
            >
              <option value="">No default template</option>
              {usableTemplates.map((template) => (
                <option key={template.id} value={template.id}>
                  {template.name} ({template.id})
                </option>
              ))}
            </select>
          </div>
          <SelectField
            id="programming-fallback-strategy"
            label="最終的な代替方式"
            value={draft.fallbackStrategy}
            options={FALLBACK_STRATEGY_OPTIONS}
            onChange={(value) => updateDraft((current) => ({ ...current, fallbackStrategy: value }))}
          />
          <NumberField
            id="programming-horizon"
            label="編成を計画する時間（分）"
            value={draft.planningHorizonMinutes}
            min={1}
            onChange={(value) => updateDraft((current) => ({ ...current, planningHorizonMinutes: value }))}
          />
          <SelectField
            id="pre-generation-mode"
            label="先行生成モード"
            value={draft.preGeneration.mode}
            options={PRE_GENERATION_MODE_OPTIONS}
            onChange={(value) => updateDraft((current) => ({ ...current, preGeneration: { ...current.preGeneration, mode: value } }))}
          />
          <NumberField
            id="pre-generation-max-minutes"
            label="先に準備する最大時間（分）"
            value={draft.preGeneration.maxPreparedMinutes}
            min={0}
            onChange={(value) => updateDraft((current) => ({ ...current, preGeneration: { ...current.preGeneration, maxPreparedMinutes: value } }))}
          />
          <NumberField
            id="pre-generation-max-blocks"
            label="先に準備する最大番組数"
            value={draft.preGeneration.maxPreparedBlocks}
            min={0}
            onChange={(value) => updateDraft((current) => ({ ...current, preGeneration: { ...current.preGeneration, maxPreparedBlocks: value } }))}
          />
          <CheckboxField
            id="pre-generation-cache-reuse"
            label="生成済みキャッシュを優先する"
            checked={draft.preGeneration.preferCacheReuse}
            description="同じ条件で利用できる生成物がある場合は、新しく生成せず再利用します。"
            onChange={(checked) => updateDraft((current) => ({ ...current, preGeneration: { ...current.preGeneration, preferCacheReuse: checked } }))}
          />
        </div>

        <div className="grid gap-4 md:grid-cols-2">
          <div className="rounded-2xl border border-slate-200 bg-white/70 px-4 py-4">
            <div className="mb-4 space-y-1">
              <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Replay Profile</div>
              <p className="text-sm leading-6 text-slate-600">再放送候補にできる segment と比率を調整します。</p>
            </div>
            <div className="space-y-4">
              <SelectField
                id="replay-intensity"
                label="再放送の強度"
                value={draft.replay.intensity}
                options={REPLAY_INTENSITY_OPTIONS}
                onChange={(value) => updateDraft((current) => ({ ...current, replay: { ...current.replay, intensity: value } }))}
              />
              <SegmentCheckboxes
                idPrefix="replay-eligible"
                selected={draft.replay.eligibleSegmentTypes}
                onChange={(segmentType, checked) =>
                  updateDraft((current) => ({
                    ...current,
                    replay: {
                      ...current.replay,
                      eligibleSegmentTypes: toggleStringList(current.replay.eligibleSegmentTypes, segmentType, checked),
                    },
                  }))
                }
              />
              <div className="grid gap-4 md:grid-cols-2">
                <NumberField
                  id="replay-min-age"
                  label="再放送まで空ける最低時間"
                  value={draft.replay.minimumAssetAgeHours}
                  min={0}
                  onChange={(value) => updateDraft((current) => ({ ...current, replay: { ...current.replay, minimumAssetAgeHours: value } }))}
                />
                <NumberField
                  id="replay-cooldown"
                  label="同じ内容を再利用しない時間"
                  value={draft.replay.cooldownHours}
                  min={0}
                  onChange={(value) => updateDraft((current) => ({ ...current, replay: { ...current.replay, cooldownHours: value } }))}
                />
                <NumberField
                  id="replay-max-share"
                  label="番組内の最大再放送比率（%）"
                  value={draft.replay.maxReplaySharePercent}
                  min={0}
                  onChange={(value) => updateDraft((current) => ({ ...current, replay: { ...current.replay, maxReplaySharePercent: value } }))}
                />
                <CheckboxField
                  id="replay-exclude-letter"
                  label="レターを再放送しない"
                  checked={draft.replay.excludeLetterSegments}
                  description="レター本文を再放送対象にしません。"
                  onChange={(checked) => updateDraft((current) => ({ ...current, replay: { ...current.replay, excludeLetterSegments: checked } }))}
                />
              </div>
            </div>
          </div>

          <div className="rounded-2xl border border-slate-200 bg-white/70 px-4 py-4">
            <div className="mb-4 space-y-1">
              <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Composition Profile</div>
              <p className="text-sm leading-6 text-slate-600">talk / letter / music / jingle の目標比率と混ぜ方を調整します。</p>
            </div>
            <div className="space-y-4">
              <div className="grid gap-4 md:grid-cols-2">
                {SHARE_KEYS.map((shareKey) => (
                  <NumberField
                    key={shareKey}
                    id={`composition-share-${shareKey}`}
                    label={`${shareKey} share`}
                    value={draft.composition.targetSegmentShares[shareKey] ?? 0}
                    min={0}
                    onChange={(value) =>
                      updateDraft((current) => ({
                        ...current,
                        composition: {
                          ...current.composition,
                          targetSegmentShares: {
                            ...current.composition.targetSegmentShares,
                            [shareKey]: value,
                          },
                        },
                      }))
                    }
                  />
                ))}
              </div>
              <Badge tone={shareTotal === 100 ? "success" : "warning"}>share total {shareTotal}</Badge>
              <div className="grid gap-4 md:grid-cols-2">
                <NumberField
                  id="composition-max-talk"
                  label="トークの最大連続数"
                  value={draft.composition.maxConsecutiveTalkSegments}
                  min={1}
                  onChange={(value) =>
                    updateDraft((current) => ({ ...current, composition: { ...current.composition, maxConsecutiveTalkSegments: value } }))
                  }
                />
                <NumberField
                  id="composition-music-interval"
                  label="音楽を挟む間隔（分）"
                  value={draft.composition.musicBreakIntervalMinutes}
                  min={1}
                  onChange={(value) =>
                    updateDraft((current) => ({ ...current, composition: { ...current.composition, musicBreakIntervalMinutes: value } }))
                  }
                />
                <NumberField
                  id="composition-letter-boost"
                  label="レターを優先する未採用件数"
                  value={draft.composition.letterPriorityBoostThreshold}
                  min={0}
                  onChange={(value) =>
                    updateDraft((current) => ({ ...current, composition: { ...current.composition, letterPriorityBoostThreshold: value } }))
                  }
                />
                <CheckboxField
                  id="composition-retiming"
                  label="代替時の尺調整を許可する"
                  checked={draft.composition.allowSoftFallbackRetiming}
                  description="SOFT の構成枠を代替する際に、番組全体へ合わせて長さを調整します。"
                  onChange={(checked) =>
                    updateDraft((current) => ({ ...current, composition: { ...current.composition, allowSoftFallbackRetiming: checked } }))
                  }
                />
              </div>
            </div>
          </div>
        </div>

        <div className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Editable Rules</div>
            <Button type="button" tone="ghost" onClick={addRule} disabled={!usableTemplates.length}>
              Add Rule
            </Button>
          </div>
          {draft.rules.length ? (
            draft.rules.map((rule, index) => (
              <div key={`${rule.templateId}-${index}`} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-4">
                <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                  <Badge tone="accent">Rule {index + 1}</Badge>
                  <Button type="button" tone="danger" onClick={() => removeRule(index)}>
                    Remove
                  </Button>
                </div>
                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
                  <NumberField
                    id={`rule-${index}-priority`}
                    label="優先度"
                    value={rule.priority}
                    min={0}
                    onChange={(value) => updateRule(index, (current) => ({ ...current, priority: value }))}
                  />
                  <div>
                    <Label htmlFor={`rule-${index}-template`}>Template</Label>
                    <select
                      id={`rule-${index}-template`}
                      className={SELECT_CLASS_NAME}
                      value={rule.templateId}
                      onChange={(event) => {
                        const value = event.currentTarget.value;
                        updateRule(index, (current) => ({ ...current, templateId: value }));
                      }}
                    >
                      {usableTemplates.map((template) => (
                        <option key={template.id} value={template.id}>
                          {template.name} ({template.id})
                        </option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <Label htmlFor={`rule-${index}-start`}>Start</Label>
                    <Input
                      id={`rule-${index}-start`}
                      type="time"
                      value={rule.startTime}
                      onChange={(event) => {
                        const value = event.currentTarget.value;
                        updateRule(index, (current) => ({ ...current, startTime: value }));
                      }}
                    />
                  </div>
                  <div>
                    <Label htmlFor={`rule-${index}-end`}>End</Label>
                    <Input
                      id={`rule-${index}-end`}
                      type="time"
                      value={rule.endTime}
                      onChange={(event) => {
                        const value = event.currentTarget.value;
                        updateRule(index, (current) => ({ ...current, endTime: value }));
                      }}
                    />
                  </div>
                  <NumberField
                    id={`rule-${index}-letters`}
                    label="必要な未採用レター数"
                    value={rule.minimumPendingLetters}
                    min={0}
                    onChange={(value) => updateRule(index, (current) => ({ ...current, minimumPendingLetters: value }))}
                  />
                </div>
                <CheckboxGroup
                  title="適用する曜日"
                  idPrefix={`rule-${index}-day`}
                  options={DAY_OPTIONS}
                  selected={rule.days}
                  onChange={(value, checked) => updateRule(index, (current) => ({ ...current, days: toggleStringList(current.days, value, checked) }))}
                />
                <CheckboxGroup
                  title="必要な Provider 状態"
                  idPrefix={`rule-${index}-provider`}
                  options={REQUIRED_PROVIDER_STATE_OPTIONS}
                  selected={rule.requiredProviderStates}
                  onChange={(value, checked) =>
                    updateRule(index, (current) => ({
                      ...current,
                      requiredProviderStates: toggleStringList(current.requiredProviderStates, value, checked),
                    }))
                  }
                />
              </div>
            ))
          ) : (
            <EmptyState title="時間帯ルールはまだありません" description="自動編成を有効にする場合は、少なくとも 1 件の時間帯ルールを追加してください。" />
          )}
        </div>
      </div>
    </SettingsSection>
  );
}

function ProgramTemplateCard({
  stations,
  templates,
  isCreatingTemplate,
  selectedTemplateId,
  template,
  draft,
  isDirty,
  isSaving,
  notice,
  error,
  onSelectTemplate,
  onStartCreateTemplate,
  onDuplicateTemplate,
  onCancelCreateTemplate,
  onDraftChange,
  onReset,
  onSave,
}: {
  stations: StationSummary[];
  templates: ProgramTemplateSummary[];
  isCreatingTemplate: boolean;
  selectedTemplateId: string | null;
  template?: ProgramTemplateDetail;
  draft: ProgramTemplateEditorDraft | null;
  isDirty: boolean;
  isSaving: boolean;
  notice: string | null;
  error: unknown;
  onSelectTemplate: (templateId: string | null) => void;
  onStartCreateTemplate: () => void;
  onDuplicateTemplate: () => void;
  onCancelCreateTemplate: () => void;
  onDraftChange: Dispatch<SetStateAction<ProgramTemplateEditorDraft | null>>;
  onReset: () => void;
  onSave: () => void;
}) {
  const updateDraft = (updater: (current: ProgramTemplateEditorDraft) => ProgramTemplateEditorDraft) => {
    onDraftChange((current) => (current ? updater(current) : current));
  };
  const saveErrorMessage = getProgramTemplateSaveErrorMessage(error, isCreatingTemplate ? "create" : "update");
  const apiFieldErrors = getApiFieldErrorMessages(error);

  const fallbackOptions = draft ? templates.filter((entry) => isTemplateUsableForDraft(entry, draft) && entry.id !== draft.id.trim()) : [];
  const duplicateSlotIds = new Set(
    (draft?.slots ?? [])
      .map((slot) => slot.slotId.trim())
      .filter((slotId, index, array) => slotId.length > 0 && array.indexOf(slotId) !== index),
  );
  const validationMessages = draft
    ? [
        draft.id.trim().length === 0 ? "Template ID は必須です。" : null,
        draft.name.trim().length === 0 ? "Template 名は必須です。" : null,
        draft.scope === "STATION" && !draft.stationId ? "STATION scope の場合は station を選択してください。" : null,
        draft.scope === "STATION" && draft.stationId && !stations.some((station) => station.id === draft.stationId)
          ? "選択した stationId が存在しません。"
          : null,
        draft.targetDurationMinutes < 1 ? "targetDurationMinutes は 1 以上で指定してください。" : null,
        draft.planningHorizonMinutes < 1 ? "planningHorizonMinutes は 1 以上で指定してください。" : null,
        draft.fallbackTemplateId && draft.fallbackTemplateId === draft.id.trim() ? "fallbackTemplateId に自分自身は指定できません。" : null,
        getJsonObjectValidationMessage(draft.editorialPolicyText, "Editorial Policy"),
        draft.slots.length === 0 ? "slot は 1 件以上必要です。" : null,
        draft.slots.some((slot) => slot.slotId.trim().length === 0) ? "各 slot に slotId が必要です。" : null,
        duplicateSlotIds.size > 0 ? `slotId は一意にしてください。重複: ${Array.from(duplicateSlotIds).join(", ")}` : null,
        draft.slots.some((slot) => slot.targetDurationMs < 1_000) ? "slot の targetDurationMs は 1000ms 以上で指定してください。" : null,
        draft.slots.some((slot) => slot.candidateSegmentTypes.length === 0) ? "各 slot で candidateSegmentTypes を 1 件以上選択してください。" : null,
        ...draft.slots.map((slot) => getJsonObjectValidationMessage(slot.slotPolicyText, `Slot Policy (${slot.slotId.trim() || "new slot"})`)),
      ].filter((message): message is string => Boolean(message))
    : [];

  const addSlot = () => {
    updateDraft((current) => ({
      ...current,
      slots: [
        ...current.slots,
        {
          ...createDefaultSlotDraft(),
          slotId: suggestSlotId(current.slots),
        },
      ],
    }));
  };

  const updateSlot = (
    index: number,
    updater: (slot: ProgramTemplateEditorDraft["slots"][number]) => ProgramTemplateEditorDraft["slots"][number],
  ) => {
    updateDraft((current) => ({
      ...current,
      slots: current.slots.map((slot, slotIndex) => (slotIndex === index ? updater(slot) : slot)),
    }));
  };

  const removeSlot = (index: number) => {
    updateDraft((current) => ({
      ...current,
      slots: current.slots.filter((_, slotIndex) => slotIndex !== index),
    }));
  };

  return (
    <Card className="mt-4">
      <SectionHeader
        eyebrow="Templates"
        title="番組テンプレート"
        description="番組の長さ、構成枠、必須条件と代替条件を定義します。保存前の入力内容は右側の編成確認でも評価でき、保存した変更は次の番組から反映されます。"
        action={
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" tone="secondary" onClick={onStartCreateTemplate}>
              新しいテンプレート
            </Button>
            <Button type="button" tone="ghost" onClick={onDuplicateTemplate} disabled={!template}>
              選択中のテンプレートを複製
            </Button>
            {isCreatingTemplate ? (
              <Button type="button" tone="ghost" onClick={onCancelCreateTemplate}>
                作成を中止
              </Button>
            ) : null}
          </div>
        }
      />
      <div className="space-y-4">
        <div>
          <Label htmlFor="program-template-select">対象のテンプレート</Label>
          <select
            id="program-template-select"
            className={SELECT_CLASS_NAME}
            value={isCreatingTemplate ? "" : (selectedTemplateId ?? "")}
            onChange={(event) => onSelectTemplate(event.currentTarget.value || null)}
          >
            {isCreatingTemplate ? <option value="">新しいテンプレートの入力内容</option> : null}
            {templates.map((entry) => (
              <option key={entry.id} value={entry.id}>
                {entry.name} ({entry.scope})
              </option>
            ))}
          </select>
        </div>

        {!templates.length && !isCreatingTemplate ? (
          <EmptyState title="番組テンプレートはまだありません" description="「新しいテンプレート」から最初の番組構成を作成できます。" />
        ) : (
          <>
            <div className="grid gap-3">
              {templates.map((entry) => (
                <button
                  key={entry.id}
                  type="button"
                  onClick={() => onSelectTemplate(entry.id)}
                  className={`interactive-control rounded-2xl border px-4 py-3 text-left ${
                    entry.id === selectedTemplateId && !isCreatingTemplate
                      ? "border-slate-950 bg-slate-950 text-white"
                      : "border-slate-200 bg-white/80 text-slate-800"
                  }`}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <div className="font-semibold">{entry.name}</div>
                    <Badge tone={entry.isActive ? "success" : "warning"}>{entry.isActive ? "ACTIVE" : "INACTIVE"}</Badge>
                    <Badge tone="default">{entry.scope}</Badge>
                    <Badge tone="accent">v{entry.version}</Badge>
                  </div>
                  <div className="mt-1 text-sm opacity-80">
                    {entry.stationId ?? "GLOBAL"} / {entry.targetDurationMinutes} min / horizon {entry.planningHorizonMinutes} min
                  </div>
                </button>
              ))}
            </div>

            {draft ? (
              <div className="space-y-4">
                {isCreatingTemplate ? (
                  <InlineNotice
                    tone="accent"
                    message="新規 template draft を編集中です。保存後に一覧へ追加され、station programming policy から参照できます。"
                  />
                ) : null}

                <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                  <Metric label="適用範囲" value={draft.scope} />
                  <Metric label="対象局" value={draft.scope === "STATION" ? draft.stationId ?? "-" : "全局共通"} />
                  <Metric label="バージョン" value={isCreatingTemplate ? "新規" : `v${draft.version}`} tone="accent" />
                  <Metric label="構成枠の数" value={draft.slots.length} />
                </div>

                <SettingsSection
                  title="テンプレートの基本情報"
                  description="テンプレート名、適用範囲、番組の目標時間、利用できない場合の代替先を指定します。保存前に編成確認で結果を確認できます。"
                >
                  <div className="space-y-4">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={isDirty ? "warning" : "success"}>{isDirty ? "テンプレートに未保存の変更があります" : "テンプレートは保存済みです"}</Badge>
                      <Badge tone="accent">{isCreatingTemplate ? "新しいテンプレート" : `テンプレートバージョン ${draft.version}`}</Badge>
                      <Button type="button" tone="ghost" onClick={onReset} disabled={!isDirty || isSaving}>
                        未保存の変更を破棄
                      </Button>
                      <Button type="button" tone="primary" onClick={onSave} disabled={!isDirty || isSaving || validationMessages.length > 0}>
                        {isSaving
                          ? isCreatingTemplate
                            ? "テンプレートを作成中..."
                            : "テンプレートを保存中..."
                          : isCreatingTemplate
                            ? "テンプレートを作成"
                            : "テンプレートを保存"}
                      </Button>
                    </div>

                    {notice ? <InlineNotice tone="accent" message={notice} /> : null}
                    {saveErrorMessage ? <InlineNotice tone="danger" message={saveErrorMessage} /> : null}
                    {apiFieldErrors.map((message) => (
                      <InlineNotice key={message} tone="danger" message={message} />
                    ))}
                    {validationMessages.map((message) => (
                      <InlineNotice key={message} tone="warning" message={message} />
                    ))}
                    <InlineNotice
                      tone="warning"
                      message="テンプレートの変更は次の番組から反映されます。右側の編成確認では、未保存の編成ポリシーとテンプレートを保存せずに評価できます。"
                    />

                    <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                      <div>
                        <Label htmlFor="template-id">テンプレート ID</Label>
                        <Input
                          id="template-id"
                          value={draft.id}
                          readOnly={!isCreatingTemplate}
                          className={!isCreatingTemplate ? "cursor-not-allowed bg-slate-100 text-slate-500" : undefined}
                          onChange={(event) => {
                            const value = event.currentTarget.value;
                            updateDraft((current) => ({ ...current, id: value }));
                          }}
                        />
                      </div>
                      <TextField
                        id="template-name"
                        label="テンプレート名"
                        value={draft.name}
                        onChange={(value) => updateDraft((current) => ({ ...current, name: value }))}
                      />
                      <SelectField
                        id="template-scope"
                        label="適用範囲"
                        value={draft.scope}
                        options={TEMPLATE_SCOPE_OPTIONS}
                        onChange={(value) =>
                          updateDraft((current) => ({
                            ...current,
                            scope: value,
                            stationId: value === "STATION" ? current.stationId ?? stations[0]?.id ?? null : null,
                          }))
                        }
                      />
                      {draft.scope === "STATION" ? (
                        <div>
                          <Label htmlFor="template-station">対象局</Label>
                          <select
                            id="template-station"
                            className={SELECT_CLASS_NAME}
                            value={draft.stationId ?? ""}
                            onChange={(event) => {
                              const value = event.currentTarget.value || null;
                              updateDraft((current) => ({ ...current, stationId: value }));
                            }}
                          >
                            <option value="">局を選択してください</option>
                            {stations.map((station) => (
                              <option key={station.id} value={station.id}>
                                {station.name} ({station.id})
                              </option>
                            ))}
                          </select>
                        </div>
                      ) : (
                        <Metric label="対象局" value="全局共通テンプレート" />
                      )}
                      <NumberField
                        id="template-duration"
                        label="番組の目標時間（分）"
                        value={draft.targetDurationMinutes}
                        min={1}
                        onChange={(value) => updateDraft((current) => ({ ...current, targetDurationMinutes: value }))}
                      />
                      <NumberField
                        id="template-horizon"
                        label="編成を計画する時間（分）"
                        value={draft.planningHorizonMinutes}
                        min={1}
                        onChange={(value) => updateDraft((current) => ({ ...current, planningHorizonMinutes: value }))}
                      />
                      <div>
                        <Label htmlFor="template-fallback">利用できない場合の代替テンプレート</Label>
                        <select
                          id="template-fallback"
                          className={SELECT_CLASS_NAME}
                          value={draft.fallbackTemplateId ?? ""}
                          onChange={(event) => {
                            const value = event.currentTarget.value || null;
                            updateDraft((current) => ({ ...current, fallbackTemplateId: value }));
                          }}
                        >
                          <option value="">代替テンプレートを指定しない</option>
                          {fallbackOptions.map((entry) => (
                            <option key={entry.id} value={entry.id}>
                              {entry.name} ({entry.id})
                            </option>
                          ))}
                        </select>
                      </div>
                      <CheckboxField
                        id="template-active"
                        label="このテンプレートを利用可能にする"
                        checked={draft.isActive}
                        description="無効にすると、新しい番組の編成候補から除外します。放送中の番組は変更しません。"
                        onChange={(checked) => updateDraft((current) => ({ ...current, isActive: checked }))}
                      />
                    </div>

                    <div>
                      <Label htmlFor="template-editorial-policy">編集方針（JSON オブジェクト）</Label>
                      <Textarea
                        id="template-editorial-policy"
                        rows={8}
                        value={draft.editorialPolicyText}
                        onChange={(event) => {
                          const value = event.currentTarget.value;
                          updateDraft((current) => ({ ...current, editorialPolicyText: value }));
                        }}
                      />
                    </div>
                  </div>
                </SettingsSection>

                <SettingsSection
                  title="番組内の構成枠"
                  description="上から順に番組で使用します。HARD は必ず守る構成、SOFT は Provider 状態などに応じて代替や尺調整を許す構成です。"
                >
                  <div className="space-y-4">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone="accent">表示順で保存</Badge>
                        <Badge tone="warning">HARD は必須、SOFT は代替可能</Badge>
                      </div>
                      <Button type="button" tone="ghost" onClick={addSlot}>
                        構成枠を追加
                      </Button>
                    </div>
                    {draft.slots.length ? (
                      draft.slots.map((slot, index) => (
                        <div key={`${slot.slotId || "slot"}-${index}`} className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-4">
                          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                            <div className="flex flex-wrap items-center gap-2">
                              <Badge tone="accent">構成枠 {index + 1}</Badge>
                              <Badge tone="default">{slot.role}</Badge>
                              <Badge tone={slot.constraintMode === "HARD" ? "danger" : "accent"}>{slot.constraintMode}</Badge>
                            </div>
                            <Button type="button" tone="danger" onClick={() => removeSlot(index)}>
                              削除
                            </Button>
                          </div>

                          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
                            <TextField
                              id={`template-slot-id-${index}`}
                              label="構成枠 ID"
                              value={slot.slotId}
                              onChange={(value) => updateSlot(index, (current) => ({ ...current, slotId: value }))}
                            />
                            <SelectField
                              id={`template-slot-role-${index}`}
                              label="番組内の役割"
                              value={slot.role}
                              options={SLOT_ROLE_OPTIONS}
                              onChange={(value) => updateSlot(index, (current) => ({ ...current, role: value as SlotRole }))}
                            />
                            <SelectField
                              id={`template-slot-constraint-${index}`}
                              label="制約の強さ"
                              value={slot.constraintMode}
                              options={CONSTRAINT_MODE_OPTIONS}
                              onChange={(value) => updateSlot(index, (current) => ({ ...current, constraintMode: value as ConstraintMode }))}
                            />
                            <NumberField
                              id={`template-slot-duration-${index}`}
                              label="目標時間（ミリ秒）"
                              value={slot.targetDurationMs}
                              min={1000}
                              step={1000}
                              onChange={(value) => updateSlot(index, (current) => ({ ...current, targetDurationMs: value }))}
                            />
                          </div>

                          <div className="mt-4 grid gap-4 lg:grid-cols-2">
                            <div>
                              <SegmentCheckboxes
                                idPrefix={`template-slot-candidate-${index}`}
                                selected={slot.candidateSegmentTypes}
                                onChange={(segmentType, checked) =>
                                  updateSlot(index, (current) => ({
                                    ...current,
                                    candidateSegmentTypes: toggleStringList(current.candidateSegmentTypes, segmentType as SegmentType, checked),
                                  }))
                                }
                              />
                            </div>
                            <div>
                              <CheckboxGroup
                                title="生成できない場合の代替候補"
                                idPrefix={`template-slot-fallback-${index}`}
                                options={SEGMENT_TYPE_OPTIONS}
                                selected={slot.fallbackSegmentTypes}
                                onChange={(segmentType, checked) =>
                                  updateSlot(index, (current) => ({
                                    ...current,
                                    fallbackSegmentTypes: toggleStringList(current.fallbackSegmentTypes, segmentType as SegmentType, checked),
                                  }))
                                }
                              />
                            </div>
                          </div>

                          <div className="mt-4">
                            <Label htmlFor={`template-slot-policy-${index}`}>構成枠の追加方針（JSON オブジェクト）</Label>
                            <Textarea
                              id={`template-slot-policy-${index}`}
                              rows={6}
                              value={slot.slotPolicyText}
                              onChange={(event) => {
                                const value = event.currentTarget.value;
                                updateSlot(index, (current) => ({ ...current, slotPolicyText: value }));
                              }}
                            />
                          </div>
                        </div>
                      ))
                    ) : (
                      <EmptyState title="構成枠はまだありません" description="保存するには「構成枠を追加」から少なくとも 1 件追加してください。" />
                    )}
                  </div>
                </SettingsSection>

                {!isCreatingTemplate && template ? (
                  <KeyValueGrid
                    title="保存済みテンプレートの情報"
                    entries={[
                      ["適用範囲", template.scope],
                      ["対象局", template.stationId ?? "全局共通"],
                      ["代替テンプレート", template.fallbackTemplateId ?? "未指定"],
                      ["保存済み構成枠", template.slots.length],
                    ]}
                  />
                ) : null}
              </div>
            ) : (
              <EmptyState title="テンプレート詳細を取得できません" description="対象のテンプレートを選択するか、「新しいテンプレート」から入力を始めてください。" />
            )}
          </>
        )}
      </div>
    </Card>
  );
}

function ProgrammingPreviewCard({
  stations,
  selectedStationId,
  previewDraft,
  previewResult,
  previewError,
  previewPending,
  previewUsesPolicyDraft,
  previewTemplateDraftId,
  onStationChange,
  onPreviewDraftChange,
  onPreview,
}: {
  stations: StationSummary[];
  selectedStationId: string | null;
  previewDraft: PreviewDraft;
  previewResult?: ProgrammingPreviewResponse;
  previewError: unknown;
  previewPending: boolean;
  previewUsesPolicyDraft: boolean;
  previewTemplateDraftId: string | null;
  onStationChange: (stationId: string | null) => void;
  onPreviewDraftChange: Dispatch<SetStateAction<PreviewDraft>>;
  onPreview: () => void;
}) {
  return (
    <Card className="mt-4">
      <SectionHeader
        eyebrow="Preview"
        title="編成結果を確認"
        description="対象局、未採用レター数、Provider 状態を指定し、保存前の編成ポリシーとテンプレートを含めて、次の番組がどのように組まれるかを確認します。確認だけでは保存や放送への反映を行いません。"
      />
      {!stations.length ? (
        <EmptyState title="確認対象の局がありません" description="「局」カテゴリーで局を追加すると編成結果を確認できます。" />
      ) : (
        <div className="space-y-4">
          {previewUsesPolicyDraft || previewTemplateDraftId ? (
            <InlineNotice
              tone="accent"
              message={
                previewUsesPolicyDraft && previewTemplateDraftId
                  ? `未保存の局別編成とテンプレート（${previewTemplateDraftId}）を含めて確認します。この操作だけでは保存されません。`
                  : previewUsesPolicyDraft
                    ? "未保存の局別編成を含めて確認します。この操作だけでは保存されません。"
                    : `未保存のテンプレート（${previewTemplateDraftId}）を含めて確認します。この操作だけでは保存されません。`
              }
            />
          ) : null}
          <div>
            <Label htmlFor="preview-station-select">対象局</Label>
            <select
              id="preview-station-select"
              className={SELECT_CLASS_NAME}
              value={selectedStationId ?? ""}
              onChange={(event) => onStationChange(event.currentTarget.value || null)}
            >
              {stations.map((entry) => (
                <option key={entry.id} value={entry.id}>
                  {entry.name}
                </option>
              ))}
            </select>
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <div>
              <Label htmlFor="preview-at">番組を開始する日時</Label>
              <Input
                id="preview-at"
                type="datetime-local"
                value={previewDraft.at}
                onChange={(event) => {
                  const value = event.currentTarget.value;
                  onPreviewDraftChange((current) => ({
                    ...current,
                    at: value,
                  }));
                }}
              />
            </div>
            <NumberField
              id="preview-pending-letters"
              label="未採用レター数"
              value={previewDraft.pendingLetterCount}
              min={0}
              onChange={(value) =>
                onPreviewDraftChange((current) => ({
                  ...current,
                  pendingLetterCount: value,
                }))
              }
            />
            {(["musicGen", "tts", "llm"] as const).map((providerKey) => (
              <div key={providerKey}>
                <Label htmlFor={`preview-${providerKey}`}>{providerKey}</Label>
                <select
                  id={`preview-${providerKey}`}
                  className={SELECT_CLASS_NAME}
                  value={previewDraft.providerStates[providerKey]}
                  onChange={(event) => {
                    const value = event.currentTarget.value;
                    onPreviewDraftChange((current) => ({
                      ...current,
                      providerStates: {
                        ...current.providerStates,
                        [providerKey]: value,
                      },
                    }));
                  }}
                >
                  {PROGRAMMING_STATE_OPTIONS.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              </div>
            ))}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button tone="primary" disabled={!selectedStationId || previewPending} onClick={onPreview}>
              {previewPending ? "編成を確認中..." : "この条件で編成を確認"}
            </Button>
            {previewUsesPolicyDraft ? <Badge tone="accent">未保存の局別編成を使用</Badge> : null}
            {previewTemplateDraftId ? <Badge tone="accent">未保存のテンプレート {previewTemplateDraftId} を使用</Badge> : null}
          </div>
          {previewResult ? (
            <div className="space-y-3 rounded-3xl border border-slate-200 bg-slate-50 px-4 py-4">
              <div className="flex flex-wrap items-center gap-2">
                <div className="text-lg font-semibold text-slate-950">{previewResult.program.title}</div>
                {previewResult.selectedTemplateId ? <Badge tone="default">{previewResult.selectedTemplateId}</Badge> : null}
                {previewResult.fallbackApplied ? <Badge tone="warning">代替構成を使用</Badge> : <Badge tone="success">テンプレートを解決済み</Badge>}
              </div>
              <div className="text-sm text-slate-600">予定時間: {formatDurationMs(previewResult.program.plannedDurationMs)}</div>
              <div className="space-y-2">
                {previewResult.slots.map((slot) => (
                  <div key={slot.slotId} className="rounded-2xl bg-white px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <div className="font-semibold text-slate-950">{slot.slotId}</div>
                      <Badge tone="default">{slot.role}</Badge>
                      <Badge tone={slot.constraintMode === "HARD" ? "danger" : "accent"}>{slot.constraintMode}</Badge>
                    </div>
                    <div className="mt-1 text-sm text-slate-600">目標時間: {formatDurationMs(slot.targetDurationMs)}</div>
                  </div>
                ))}
              </div>
              {previewResult.validationWarnings.length ? (
                <div className="space-y-2">
                  <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">編成上の注意</div>
                  {previewResult.validationWarnings.map((warning) => (
                    <InlineNotice key={warning.code} tone="warning" message={`${warning.code}: ${warning.message}`} />
                  ))}
                </div>
              ) : null}
            </div>
          ) : previewError instanceof Error ? (
            <EmptyState title="編成結果を取得できません" description={previewError.message} />
          ) : (
            <EmptyState title="編成結果はまだ確認していません" description="対象局と Provider 状態を指定して確認を実行すると、次の番組候補をここに表示します。" />
          )}
        </div>
      )}
    </Card>
  );
}

function KeyValueGrid({ title, entries }: { title: string; entries: Array<[string, ReactNode]> }) {
  return (
    <div className="rounded-3xl border border-slate-200 bg-slate-50 px-4 py-4">
      <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">{title}</div>
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        {entries.map(([label, value]) => (
          <div key={label}>
            <div className="text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-400">{label}</div>
            <div className="mt-1 text-sm leading-6 text-slate-700">{value}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

function buildProgrammingPreviewRequest({
  stationId,
  previewDraft,
  programmingDraft,
  savedProgramming,
  isProgrammingDirty,
  templateDraft,
  templateEditorMode,
  isTemplateDirty,
}: {
  stationId: string;
  previewDraft: PreviewDraft;
  programmingDraft: StationProgrammingUpdateRequest | null;
  savedProgramming?: StationProgrammingResponse;
  isProgrammingDirty: boolean;
  templateDraft: ProgramTemplateEditorDraft | null;
  templateEditorMode: "existing" | "create";
  isTemplateDirty: boolean;
}): ProgrammingPreviewRequest {
  const policyDraft = programmingDraft && isProgrammingDirty ? programmingDraft : null;
  const templateDraftId = resolvePreviewTemplateDraftId({
    stationId,
    programming: policyDraft ?? savedProgramming,
    templateDraft,
    isTemplateDirty,
    templateEditorMode,
  });
  const templatePayload =
    templateDraftId && templateDraft && templateDraft.id.trim() === templateDraftId ? serializeProgramTemplateDraft(templateDraft) : null;

  return {
    at: toPreviewIso(previewDraft.at),
    pendingLetterCount: previewDraft.pendingLetterCount,
    providerStates: previewDraft.providerStates,
    policyDraft,
    templateDraft: templatePayload,
  };
}

function resolvePreviewTemplateDraftId({
  stationId,
  programming,
  templateDraft,
  isTemplateDirty,
  templateEditorMode,
}: {
  stationId: string;
  programming?: ProgrammingTemplateReferenceSource;
  templateDraft: ProgramTemplateEditorDraft | null;
  isTemplateDirty: boolean;
  templateEditorMode: "existing" | "create";
}) {
  if (!templateDraft || (templateEditorMode !== "create" && !isTemplateDirty)) {
    return null;
  }
  const templateId = templateDraft.id.trim();
  if (!templateId || !doesTemplateDraftApplyToStation(templateDraft, stationId)) {
    return null;
  }
  if (!programming || !doesProgrammingReferenceTemplate(programming, templateId)) {
    return null;
  }
  return templateId;
}

type ProgrammingTemplateReferenceSource = {
  defaultTemplateId: string | null;
  rules: Array<{ templateId: string }>;
};

function doesProgrammingReferenceTemplate(programming: ProgrammingTemplateReferenceSource, templateId: string) {
  return programming.defaultTemplateId === templateId || programming.rules.some((rule) => rule.templateId === templateId);
}

function doesTemplateDraftApplyToStation(draft: Pick<ProgramTemplateEditorDraft, "scope" | "stationId">, stationId: string) {
  return draft.scope === "GLOBAL" || (draft.scope === "STATION" && draft.stationId === stationId);
}

function getProgramTemplateSaveErrorMessage(error: unknown, mode: "create" | "update") {
  if (error instanceof ApiRequestError) {
    const safeMessage = formatSafeDisplayText(error.message);
    if (safeMessage && safeMessage !== REDACTED_METADATA_VALUE) {
      return safeMessage;
    }
    if (error.status === 400) {
      return mode === "create"
        ? "ProgramTemplate を作成できませんでした。scope / station / slot / segmentType の整合を見直してから再度保存してください。"
        : "ProgramTemplate を保存できませんでした。scope / station / slot / segmentType の整合を見直してから再度保存してください。";
    }
    if (error.status === 409) {
      return mode === "create"
        ? "同じ Template ID の ProgramTemplate が既に存在するため作成できません。別の Template ID に変更して再度保存してください。"
        : "ProgramTemplate の保存が競合しました。最新の template を読み直してから再度保存してください。";
    }
  }
  return error instanceof Error ? formatSafeDisplayText(error.message) : null;
}

function getSettingsSaveErrorMessage(error: Error, page: SettingsPage) {
  if (!(error instanceof ApiRequestError)) {
    return formatSafeDisplayText(error.message);
  }
  if (error.status === 409) {
    return "別の操作で設定が更新されました。画面を再読み込みし、最新の内容を確認してからもう一度保存してください。";
  }
  if (page !== "providers") {
    return formatSafeDisplayText(error.message);
  }
  const field = error.field ?? "";
  const target = describeProviderField(field);
  if (field.endsWith(".baseUrl")) {
    return `${target}の接続先 URL が正しくありません。例: Ollama は http://127.0.0.1:11434、ACE-Step は http://127.0.0.1:8001 です。`;
  }
  if (field.endsWith(".adapter")) {
    return `${target}の接続方式を選択してください。Ollama は OLLAMA、ACE-Step は ACE_STEP を指定します。`;
  }
  if (field.endsWith(".defaultModelProfileId")) {
    return `${target}で使用するモデルを指定してください。LLM は Ollama のモデル名、ACE-Step は画面に表示された生成プロファイル ID を指定します。`;
  }
  if (field.endsWith(".apiKeyRef")) {
    return `${target}の API キーは秘密値そのものではなく、env:ACESTEP_API_KEY のような参照先を指定してください。`;
  }
  return "Provider 設定を保存できませんでした。接続先 URL、接続方式、モデル名、API キーの参照先を確認してください。";
}

export function validateProviderCatalog(providers: ProviderCatalog) {
  const errors: string[] = [];
  for (const groupKey of PROVIDER_GROUPS) {
    for (const [providerKey, endpoint] of Object.entries(providers[groupKey].providers)) {
      const target = `${PROVIDER_LABELS[groupKey]}「${providerKey}」`;
      if (!isValidProviderBaseUrl(endpoint.baseUrl)) {
        errors.push(`${target}の接続先 URL が正しくありません。`);
      }
      if (groupKey === "llm") {
        if (!LLM_PROVIDER_ADAPTER_OPTIONS.includes(endpoint.adapter as (typeof LLM_PROVIDER_ADAPTER_OPTIONS)[number])) {
          errors.push(`${target}の LLM 接続方式を選択してください。`);
        }
        if (!endpoint.defaultModelProfileId?.trim()) {
          errors.push(`${target}の台本生成モデル名を指定してください。`);
        }
      }
      if (
        groupKey === "musicGen"
        && endpoint.defaultModelProfileId
        && !Object.prototype.hasOwnProperty.call(endpoint.modelProfiles ?? {}, endpoint.defaultModelProfileId)
      ) {
        errors.push(`${target}の既定生成プロファイルは、利用可能な生成プロファイルから選択してください。`);
      }
    }
  }
  return errors;
}

function isValidProviderBaseUrl(value: string) {
  try {
    const url = new URL(value);
    if (!["http:", "https:"].includes(url.protocol) || !url.hostname) {
      return false;
    }
    return !url.hostname.startsWith(".") && !url.hostname.endsWith(".") && !url.hostname.includes("..");
  } catch {
    return false;
  }
}

function describeProviderField(field: string) {
  const match = /^providers\.(llm|tts|musicGen)\.([^.]+)/.exec(field);
  if (!match) {
    return "Provider ";
  }
  const groupKey = match[1] as keyof ProviderCatalog;
  return `${PROVIDER_LABELS[groupKey]}「${match[2]}」`;
}

export function inferLlmAdapter(providerKey: string, endpoint: ProviderEndpoint) {
  if (endpoint.adapter === "OLLAMA" || endpoint.adapter === "OPENAI_COMPATIBLE") {
    return endpoint.adapter;
  }
  const providerHint = `${providerKey} ${endpoint.baseUrl} ${endpoint.healthPath}`.toLowerCase();
  return providerHint.includes("ollama") || providerHint.includes("11434") || providerHint.includes("/api/tags")
    ? "OLLAMA"
    : "OPENAI_COMPATIBLE";
}

function getSameHostProviderPreset(groupKey: keyof ProviderCatalog, providerKey: string, endpoint: ProviderEndpoint) {
  if (groupKey === "llm" && inferLlmAdapter(providerKey, endpoint) === "OLLAMA") {
    return {
      baseUrl: "http://127.0.0.1:11434",
      healthPath: "/api/tags",
      adapter: "OLLAMA",
      defaultModelProfileId: "qwen3:8b",
    };
  }
  if (groupKey === "musicGen" && (endpoint.adapter === "ACE_STEP" || providerKey.toLowerCase().includes("ace"))) {
    return {
      baseUrl: "http://127.0.0.1:8001",
      healthPath: "/health",
      adapter: "ACE_STEP",
      defaultModelProfileId: "ace-ja-fast",
    };
  }
  return null;
}

function getDetectedProviderModels(health: ProviderHealthPayload | undefined, providerKey: string) {
  if (!health || health.providerKey !== providerKey || !health.metadata || !Array.isArray(health.metadata.models)) {
    return [];
  }
  return health.metadata.models.filter((model): model is string => typeof model === "string");
}

export function aceStepModelMatches(catalogModel: string, selectedModel: string) {
  if (!catalogModel.trim() || !selectedModel.trim()) {
    return false;
  }
  return catalogModel === selectedModel
    || catalogModel.endsWith(`/${selectedModel}`)
    || catalogModel.endsWith(` ${selectedModel}`);
}

function getApiFieldErrorMessages(error: unknown) {
  if (!(error instanceof ApiRequestError)) {
    return [];
  }
  return Object.entries(error.fieldErrors).map(([field, message]) => `${field}: ${formatSafeDisplayText(message)}`);
}

function createPreviewDraft(): PreviewDraft {
  return {
    at: toLocalDateTimeValue(new Date()),
    pendingLetterCount: 0,
    providerStates: {
      musicGen: "UNKNOWN",
      tts: "UNKNOWN",
      llm: "UNKNOWN",
    },
  };
}

function toPreviewIso(value: string) {
  const date = value ? new Date(value) : new Date();
  if (Number.isNaN(date.getTime())) {
    return new Date().toISOString();
  }
  return date.toISOString();
}

function toLocalDateTimeValue(date: Date) {
  const offsetMs = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offsetMs).toISOString().slice(0, 16);
}

function formatDurationMs(value: number) {
  const totalSeconds = Math.max(0, Math.round(value / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}m ${seconds}s`;
}

function formatSegmentTypes(values: readonly string[]) {
  return values.length ? values.join(", ") : "-";
}

function formatShareMap(value: Record<string, number>) {
  return Object.entries(value)
    .map(([key, share]) => `${key}:${share}`)
    .join(" / ");
}

function formatJson(value: Record<string, unknown>) {
  const entries = Object.entries(value);
  if (!entries.length) {
    return "{}";
  }
  return entries
    .map(([key, entryValue]) => `${key}=${String(entryValue)}`)
    .join(", ");
}
