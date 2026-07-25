import { describe, expect, it } from "vitest";
import { formatBytes } from "@/components/management-dashboard";

describe("management dashboard helpers", () => {
  it("局別 asset 容量を読みやすい単位へ変換する", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1024)).toBe("1.00 KB");
    expect(formatBytes(12 * 1024 * 1024)).toBe("12.0 MB");
  });
});
