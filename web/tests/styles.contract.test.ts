import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const stylesPath = resolve(process.cwd(), "src/styles.css");
const htmlPath = resolve(process.cwd(), "index.html");
const packagePath = resolve(process.cwd(), "package.json");

describe("responsive style contract", () => {
  it("supports narrow, tablet and wide layouts without fixed viewport widths", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toContain("@media (min-width: 48rem)");
    expect(css).toContain("@media (min-width: 64rem)");
    expect(css).toContain(".shell-layout");
    expect(css).toContain("grid-template-columns: minmax(18rem, 0.72fr) minmax(0, 1.68fr)");
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

  it("keeps state, item and progress motion short and fully disables it on request", () => {
    const css = readFileSync(stylesPath, "utf8");
    const root = css.match(/:root\s*\{(?<body>.*?)\n\}/s)?.groups?.body ?? "";
    const reduced = css.match(
      /@media \(prefers-reduced-motion: reduce\)\s*\{(?<body>.*?)\n\}/s,
    )?.groups?.body ?? "";

    expect(root).toContain("--motion-duration-fast: 120ms");
    expect(root).toContain("--motion-duration-standard: 180ms");
    expect(css).toMatch(/\.status-card\s*\{[^}]*transition:/s);
    expect(css).toMatch(/\.(?:text-card|file-card)[^}]*animation:\s*item-enter/s);
    expect(css).toMatch(/\.file-card progress\s*\{[^}]*transition:/s);
    expect(reduced).toContain("animation: none !important");
    expect(reduced).toContain("transition: none !important");
  });
  it("defines explicit hover, pressed, focused, disabled and loading action states", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toMatch(/\.primary-button:hover:not\(:disabled\)/);
    expect(css).toMatch(/\.primary-button:active:not\(:disabled\)/);
    expect(css).toMatch(/\.secondary-button:disabled/);
    expect(css).toMatch(/\.text-card__(?:copy|open):hover:not\(:disabled\)/);
    expect(css).toMatch(/\.text-card__(?:copy|open):active:not\(:disabled\)/);
    expect(css).toMatch(/\[data-view-state="loading"\]/);
  });
  it("keeps text and file actions inside viewports from 360 to 1920 pixels", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toContain("@media (max-width: 30rem)");
    expect(css).toMatch(/\.text-transfer\s*\{[^}]*min-width:\s*0/s);
    expect(css).toMatch(/\.text-card\s*\{[^}]*min-width:\s*0/s);
    expect(css).toMatch(/\.text-card__actions\s*\{[^}]*display:\s*grid/s);
    expect(css).toMatch(/\.text-card__actions\s*\{[^}]*grid-template-columns:\s*repeat\(auto-fit,/s);
    expect(css).toMatch(/\.text-card__actions button\s*\{[^}]*max-width:\s*100%/s);
    expect(css).toMatch(/\.file-transfer\s*\{[^}]*min-width:\s*0/s);
    expect(css).toMatch(/\.file-card\s*\{[^}]*min-width:\s*0/s);
    expect(css).toMatch(/\.file-card__actions\s*\{[^}]*flex-wrap:\s*wrap/s);
    expect(css).toContain("overflow-x: hidden");
    expect(css).not.toMatch(/(?:min-)?width:\s*(?:360|1920)px/);
    expect(css).not.toContain("100vw");
  });
  it("defines the complete semantic token contract in light and dark themes", () => {
    const css = readFileSync(stylesPath, "utf8");
    const root = css.match(/:root\s*\{(?<body>.*?)\n\}/s)?.groups?.body ?? "";
    const dark =
      css.match(
        /@media \(prefers-color-scheme: dark\)\s*\{\s*:root\s*\{(?<body>.*?)\n\s*\}\s*\}/s,
      )?.groups?.body ?? "";
    const requiredTokens = [
      "--color-bg-canvas",
      "--color-bg-surface",
      "--color-bg-subtle",
      "--color-bg-elevated",
      "--color-fg-primary",
      "--color-fg-muted",
      "--color-border-subtle",
      "--color-border-strong",
      "--color-action-primary",
      "--color-action-primary-container",
      "--color-focus-ring",
      "--color-status-info",
      "--color-status-info-container",
      "--color-status-success",
      "--color-status-success-container",
      "--color-status-warning",
      "--color-status-warning-container",
      "--color-status-error",
      "--color-status-error-container",
      "--font-family-sans",
      "--font-family-mono",
      "--font-size-body",
      "--font-size-label",
      "--font-size-title",
      "--font-size-display",
      "--line-height-body",
      "--font-weight-regular",
      "--font-weight-semibold",
      "--font-weight-bold",
      "--space-1",
      "--space-2",
      "--space-3",
      "--space-4",
      "--space-6",
      "--space-8",
      "--shape-small",
      "--shape-medium",
      "--shape-large",
      "--shape-pill",
      "--border-width-subtle",
      "--border-width-strong",
      "--border-width-focus",
      "--focus-offset",
      "--elevation-raised",
      "--elevation-overlay",
    ];
    const themeColorTokens = requiredTokens.filter((token) =>
      token.startsWith("--color-"),
    );

    for (const token of requiredTokens) {
      expect(root, `${token} must be defined in :root`).toContain(`${token}:`);
    }
    for (const token of themeColorTokens) {
      expect(dark, `${token} must be overridden in dark theme`).toContain(`${token}:`);
    }
    expect(css).toContain("font-family: var(--font-family-sans)");
    expect(css).toContain(
      "outline: var(--border-width-focus) solid var(--color-focus-ring)",
    );
    expect(css).toContain("outline-offset: var(--focus-offset)");
  });

  it("uses only bundled CSS and system fonts without runtime styling dependencies", () => {
    const css = readFileSync(stylesPath, "utf8");
    const html = readFileSync(htmlPath, "utf8");
    const packageJson = JSON.parse(readFileSync(packagePath, "utf8")) as {
      dependencies?: Record<string, string>;
      devDependencies?: Record<string, string>;
    };
    const packages = Object.keys({
      ...packageJson.dependencies,
      ...packageJson.devDependencies,
    });

    expect(css).not.toMatch(/@import\s|url\s*\(/i);
    expect(css).not.toMatch(/https?:\/\//i);
    expect(css).not.toContain("Inter");
    expect(html).not.toMatch(/<(?:link|script)[^>]+https?:\/\//i);
    expect(html).not.toMatch(/\sstyle=/i);
    expect(packages).not.toContain(expect.stringMatching(/styled|emotion|tailwind/i));
  });
});
