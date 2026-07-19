import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { Button, Card, Input } from "@/components/ui";

describe("UI motion and interaction primitives", () => {
  it("keeps card entrance motion opt-in", () => {
    const staticCard = renderToStaticMarkup(createElement(Card, null, "Static"));
    const enteringCard = renderToStaticMarkup(createElement(Card, { motion: "enter" }, "Entering"));

    expect(staticCard).not.toContain("motion-enter");
    expect(enteringCard).toContain("motion-enter");
  });

  it("applies the shared interaction contract to buttons", () => {
    const markup = renderToStaticMarkup(createElement(Button, { tone: "secondary" }, "Tune"));

    expect(markup).toContain("interactive-control");
    expect(markup).toContain("min-h-11");
    expect(markup).toContain("bg-teal-700");
  });

  it("applies the shared focus transition contract to fields", () => {
    const markup = renderToStaticMarkup(createElement(Input, { "aria-label": "Station" }));

    expect(markup).toContain("field-control");
    expect(markup).toContain("min-h-11");
  });
});
