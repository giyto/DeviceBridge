import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const stylesPath = resolve(process.cwd(), "src/styles.css");

describe("responsive style contract", () => {
  it("supports narrow, tablet and wide layouts without fixed viewport widths", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toContain("@media (min-width: 48rem)");
    expect(css).toContain("@media (min-width: 80rem)");
    expect(css).toContain("max-width:");
    expect(css).toContain("overflow-wrap: anywhere");
    expect(css).not.toMatch(/width:\s*(?:360|768|1920)px/);
  });

  it("supports both system color schemes", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toContain("color-scheme: light dark");
    expect(css).toContain("@media (prefers-color-scheme: dark)");
    expect(css).toContain("--surface:");
    expect(css).toContain("--text:");
  });

  it("keeps focus visible, touch targets large and reduced motion respected", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toContain(":focus-visible");
    expect(css).toMatch(/min-height:\s*44px/);
    expect(css).toContain("@media (prefers-reduced-motion: reduce)");
  });

  it("keeps text and file actions inside viewports from 360 to 1920 pixels", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toContain("@media (max-width: 30rem)");
    expect(css).toMatch(/\.text-transfer\s*\{[^}]*min-width:\s*0/s);
    expect(css).toMatch(/\.text-card\s*\{[^}]*min-width:\s*0/s);
    expect(css).toMatch(/\.file-transfer\s*\{[^}]*min-width:\s*0/s);
    expect(css).toMatch(/\.file-card\s*\{[^}]*min-width:\s*0/s);
    expect(css).toMatch(/\.file-card__actions\s*\{[^}]*flex-wrap:\s*wrap/s);
    expect(css).toContain("overflow-x: hidden");
    expect(css).not.toMatch(/(?:min-)?width:\s*(?:360|1920)px/);
    expect(css).not.toContain("100vw");
  });
});
