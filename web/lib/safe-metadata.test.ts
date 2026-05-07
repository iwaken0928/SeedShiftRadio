import { describe, expect, it } from "vitest";
import { formatSafeDisplayText, formatSafeMetadataValue, getSafeMetadataEntries, REDACTED_METADATA_VALUE } from "@/lib/safe-metadata";

describe("safe-metadata", () => {
  it("secret や本文系 key の metadata を redaction する", () => {
    const entries = getSafeMetadataEntries({
      apiKey: "sk-secret",
      adminTokenRef: "plain-token",
      prompt: "台本生成 prompt",
      lyrics: "歌詞全文",
      letterBody: "お便り本文",
      radioName: "秘密の番組名",
      adapter: "worker-http",
    });

    expect(entries).toEqual([
      { key: "apiKey", value: REDACTED_METADATA_VALUE, redacted: true },
      { key: "adminTokenRef", value: REDACTED_METADATA_VALUE, redacted: true },
      { key: "prompt", value: REDACTED_METADATA_VALUE, redacted: true },
      { key: "lyrics", value: REDACTED_METADATA_VALUE, redacted: true },
      { key: "letterBody", value: REDACTED_METADATA_VALUE, redacted: true },
      { key: "radioName", value: REDACTED_METADATA_VALUE, redacted: true },
      { key: "adapter", value: "worker-http", redacted: false },
    ]);
  });

  it("nested object に sensitive key が混ざる場合は値全体を redaction する", () => {
    expect(formatSafeMetadataValue("defaultModel", { model: "musicgen", apiKey: "sk-secret" })).toBe(REDACTED_METADATA_VALUE);
    expect(formatSafeMetadataValue("models", [{ name: "musicgen" }, { prompt: "raw prompt" }])).toBe(REDACTED_METADATA_VALUE);
  });

  it("secret らしい inline text と credential URL を redaction する", () => {
    expect(formatSafeDisplayText("authorization: Bearer abc")).toBe(REDACTED_METADATA_VALUE);
    expect(formatSafeDisplayText("https://user:password@example.test/health")).toBe(REDACTED_METADATA_VALUE);
  });

  it("安全な短い状態値は整形し、長い値は切り詰める", () => {
    expect(formatSafeMetadataValue("models", ["musicgen-small", { model: "musicgen-medium" }])).toBe(
      'musicgen-small, {"model":"musicgen-medium"}',
    );
    expect(formatSafeMetadataValue("message", "x".repeat(200))).toBe(`${"x".repeat(160)}...`);
  });
});
