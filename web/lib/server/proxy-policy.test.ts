import { describe, expect, it } from "vitest";
import { requiresAdminSession } from "@/lib/server/proxy-policy";

describe("proxy policy", () => {
  it.each([
    ["GET", ["api", "settings"]],
    ["GET", ["api", "monitor", "summary"]],
    ["GET", ["api", "management", "dashboard"]],
    ["POST", ["api", "management", "stations", "station-1", "pre-generations"]],
    ["GET", ["api", "play-history"]],
    ["GET", ["api", "play-history", "history-1"]],
    ["GET", ["api", "letters"]],
    ["POST", ["api", "letters", "letter-1", "reply"]],
    ["GET", ["api", "stations", "station-1", "programming"]],
  ])("%s /%s は管理 session を要求する", (method, path) => {
    expect(requiresAdminSession(method, path)).toBe(true);
  });

  it.each([
    ["GET", ["api", "health"]],
    ["GET", ["api", "stations"]],
    ["POST", ["api", "letters"]],
    ["POST", ["api", "letters", "public", "history"]],
  ])("%s /%s は公開契約を維持する", (method, path) => {
    expect(requiresAdminSession(method, path)).toBe(false);
  });

  it("将来の letters/public 配下を暗黙に公開しない", () => {
    expect(requiresAdminSession("POST", ["api", "letters", "public", "history", "extra"])).toBe(true);
    expect(requiresAdminSession("GET", ["api", "letters", "public", "history"])).toBe(true);
  });
});
