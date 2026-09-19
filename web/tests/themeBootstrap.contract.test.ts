import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("pre-render theme bootstrap", () => {
  it("runs before CSS, supports only light/dark and maps legacy system to dark", () => {
    const html = readFileSync(resolve(process.cwd(), "index.html"), "utf8");
    const bootstrapStart = html.indexOf('data-role="theme-bootstrap"');
    const stylesheetStart = html.indexOf('rel="stylesheet"');
    const moduleStart = html.indexOf('type="module"');

    expect(bootstrapStart).toBeGreaterThan(0);
    expect(bootstrapStart).toBeLessThan(stylesheetStart);
    expect(bootstrapStart).toBeLessThan(moduleStart);
    expect(html).toContain("devicebridge.theme.v1");
    expect(html).toContain('const allowed = ["light", "dark"]');
    expect(html).toContain('record.preference === "system"');
    expect(html).toContain('preference = "dark"');
    expect(html).not.toContain("prefers-color-scheme: dark");
    expect(html).not.toContain('typeof matchMedia === "function"');
    expect(html).toContain("document.documentElement.dataset.theme");
  });
});
