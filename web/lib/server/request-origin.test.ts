import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import { hasSameRequestOrigin } from "@/lib/server/request-origin";

const rejectionCases: Array<{ label: string; headers: Record<string, string> }> = [
  {
    label: "Origin 欠落",
    headers: { host: "127.0.0.1:3001" },
  },
  {
    label: "Host 不一致",
    headers: { origin: "http://attacker.invalid", host: "127.0.0.1:3001" },
  },
  {
    label: "protocol 不一致",
    headers: {
      origin: "https://radio.example.test",
      host: "radio.example.test",
      "x-forwarded-proto": "http",
    },
  },
  {
    label: "不正な Origin",
    headers: { origin: "not-a-url", host: "127.0.0.1:3001" },
  },
  {
    label: "path を含む Origin",
    headers: { origin: "http://127.0.0.1:3001/admin/login", host: "127.0.0.1:3001" },
  },
];

describe("request origin", () => {
  it("Host と protocol が一致する直接 request を許可する", () => {
    const request = requestWithHeaders({
      origin: "http://127.0.0.1:3001",
      host: "127.0.0.1:3001",
    });

    expect(hasSameRequestOrigin(request)).toBe(true);
  });

  it("Next.js 内部 URL と異なっても公開 Host と Origin が一致すれば許可する", () => {
    const request = requestWithHeaders({
      origin: "http://127.0.0.1:3001",
      host: "127.0.0.1:3001",
    }, "http://localhost:3000/api/auth/login");

    expect(hasSameRequestOrigin(request)).toBe(true);
  });

  it("forwarded header の先頭要素から公開 origin を復元する", () => {
    const request = requestWithHeaders({
      origin: "https://radio.example.test",
      host: "web:3000",
      "x-forwarded-host": "radio.example.test, internal-proxy:8443",
      "x-forwarded-proto": "https, http",
    });

    expect(hasSameRequestOrigin(request)).toBe(true);
  });

  it.each(rejectionCases)("$label を拒否する", ({ headers }) => {
    expect(hasSameRequestOrigin(requestWithHeaders(headers))).toBe(false);
  });
});

function requestWithHeaders(headers: Record<string, string>, url = "http://127.0.0.1:3001/api/auth/login") {
  return new NextRequest(url, { headers });
}
