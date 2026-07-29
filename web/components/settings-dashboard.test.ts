import { describe, expect, it } from "vitest";
import { aceStepModelMatches, inferLlmAdapter, validateProviderCatalog } from "@/components/settings-dashboard";
import type { ProviderCatalog, ProviderEndpoint } from "@/lib/types";

const ollamaEndpoint: ProviderEndpoint = {
  baseUrl: "http://127.0.0.1:11434",
  healthPath: "/api/tags",
  timeoutMs: 20_000,
  capabilities: ["SCRIPT_GEN"],
  adapter: "OLLAMA",
  defaultModelProfileId: "qwen3:8b",
  modelProfiles: {},
};

describe("Provider 設定入力", () => {
  it("Ollama の既存 endpoint から接続方式を補完する", () => {
    expect(inferLlmAdapter("ollama", { ...ollamaEndpoint, adapter: undefined })).toBe("OLLAMA");
  });

  it("ACE-Stepのnamespace付きIDと表示名を生成プロファイルのモデルへ照合する", () => {
    expect(aceStepModelMatches("acestep/acestep-v15-turbo", "acestep-v15-turbo")).toBe(true);
    expect(aceStepModelMatches("ACE-Step acestep-v15-turbo", "acestep-v15-turbo")).toBe(true);
    expect(aceStepModelMatches("acestep-v15-xl-turbo", "acestep-v15-turbo")).toBe(false);
  });

  it("誤った URL と未指定モデルを対象 Provider が分かる日本語で検出する", () => {
    const providers: ProviderCatalog = {
      llm: {
        defaultProvider: "ollama",
        fallbackProviders: [],
        providers: {
          ollama: {
            ...ollamaEndpoint,
            baseUrl: "http://192..168.0.30:11434",
            defaultModelProfileId: null,
          },
        },
      },
      tts: {
        defaultProvider: "voicevox",
        fallbackProviders: [],
        providers: {
          voicevox: {
            baseUrl: "http://127.0.0.1:50021",
            healthPath: "/version",
            timeoutMs: 5_000,
            capabilities: ["TTS_GEN"],
            adapter: "VOICEVOX",
          },
        },
      },
      musicGen: {
        defaultProvider: "ace-step",
        fallbackProviders: [],
        providers: {
          "ace-step": {
            baseUrl: "http://127.0.0.1:8001",
            healthPath: "/health",
            timeoutMs: 10_000,
            capabilities: ["MUSIC_GEN", "ACE_STEP"],
            adapter: "ACE_STEP",
            defaultModelProfileId: "ace-ja-fast",
            modelProfiles: {
              "ace-ja-fast": {
                model: "acestep-v15-turbo",
                lmModel: "acestep-5Hz-lm-0.6B",
                thinking: true,
                lyricsLanguage: "ja",
                lyricsTransliterationMode: "native",
                outputFormat: "wav",
                maxDurationSeconds: 120,
              },
            },
          },
        },
      },
    };

    expect(validateProviderCatalog(providers)).toEqual([
      "LLM「ollama」の接続先 URL が正しくありません。",
      "LLM「ollama」の台本生成モデル名を指定してください。",
    ]);
  });
});
