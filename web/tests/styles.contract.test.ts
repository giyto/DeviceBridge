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

  it("uses a flat Graphite workspace and a stable wide connection column", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toMatch(/\.shell-layout__connection\s*\{[^}]*overflow-wrap:\s*anywhere/s);
    expect(css).toMatch(/\.shell-layout__workspace\s*\{[^}]*max-width:/s);
    expect(css).toMatch(
      /\.stage-card\s*\{[^}]*border:\s*0[^}]*background:\s*transparent/s,
    );
    expect(css).toMatch(
      /@media \(min-width: 64rem\)[\s\S]*\.shell-layout__connection\s*\{[^}]*position:\s*sticky[^}]*top:/,
    );
  });
  it("supports controller-selected light and dark color schemes", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toContain("color-scheme: light");
    expect(css).toContain(':root[data-theme="dark"]');
    expect(css).toContain("color-scheme: dark");
    expect(css).not.toContain("@media (prefers-color-scheme: dark)");
    expect(css).toContain("--surface:");
    expect(css).toContain("--text:");
  });

  it("keeps focus visible, touch targets large and reduced motion respected", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toContain(":focus-visible");
    expect(css).toMatch(/min-height:\s*44px/);
    expect(css).toContain("@media (prefers-reduced-motion: reduce)");
  });

  it("uses finite Signal Flow feedback for active connection and transfer states", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toContain("@keyframes signal-flow");
    expect(css).toMatch(
      /\.status-card\[data-view-state="loading"\] \.status-card__pulse\s*\{[^}]*animation:\s*status-dot-flash/s,
    );
    expect(css).toMatch(
      /\.file-card\[data-view-state="loading"\]::before\s*\{[^}]*animation:\s*signal-flow/s,
    );
    expect(css).not.toMatch(/animation:[^;\n]*infinite/);
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
        /:root\[data-theme="dark"\]\s*\{(?<body>.*?)\n\}/s,
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
  it("styles one compact theme icon at the right with visible keyboard focus", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toMatch(/\.theme-control-panel\s*\{[^}]*justify-content:\s*flex-end/s);
    expect(css).toMatch(/\.theme-control\s*\{[^}]*width:\s*44px[^}]*height:\s*44px/s);
    expect(css).toMatch(/\.theme-control svg\s*\{[^}]*width:\s*20px[^}]*height:\s*20px/s);
    expect(css).toMatch(/\.theme-control:focus-visible/);
    expect(css).not.toContain(".theme-control-label");
    expect(css).not.toMatch(/\.stage-card\[data-view-state=[^}]+border-top/s);
    expect(css).toMatch(/\.hero h1\s*\{[^}]*white-space:\s*nowrap/s);
  });
  it("uses a bounded file draft surface and flat operational transfer rows", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toMatch(
      /\.file-drop-zone\s*\{[^}]*min-width:\s*0[^}]*border:[^}]*background:\s*var\(--surface-subtle\)/s,
    );
    expect(css).toMatch(
      /\.file-card\s*\{[^}]*border:\s*0[^}]*border-bottom:[^}]*border-radius:\s*0[^}]*background:\s*transparent/s,
    );
    expect(css).toMatch(
      /\.file-card\[data-view-state="loading"\][^}]*\.file-card__status\s*\{[^}]*--color-status-info/s,
    );
    expect(css).toMatch(/\.file-card__heading strong\s*\{[^}]*overflow-wrap:\s*anywhere/s);
    expect(css).toMatch(/\.file-card__actions\s*\{[^}]*flex-wrap:\s*wrap/s);
  });
  it("uses one composer surface and flat operational rows for the text feed", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toMatch(
      /\.text-form\s*\{[^}]*padding:[^}]*border:[^}]*background:\s*var\(--surface\)/s,
    );
    expect(css).toMatch(
      /\.text-card\s*\{[^}]*border:\s*0[^}]*border-bottom:[^}]*border-radius:\s*0[^}]*background:\s*transparent/s,
    );
    expect(css).toMatch(
      /\.text-card\[data-view-state="loading"\][^}]*\.text-card__status\s*\{[^}]*--color-status-info/s,
    );
    expect(css).toMatch(/\.text-card__content\s*\{[^}]*overflow-wrap:\s*anywhere/s);
  });
  it("keeps session state semantic without a decorative top rail", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).not.toMatch(/\.stage-card\[data-view-state="(?:loading|ready|offline|error)"\]\s*\{/);
    expect(css).toMatch(/\.status-card\[data-state="ready"\] \.status-card__pulse/);
    expect(css).toMatch(/\.status-card\[data-state="offline"\] \.status-card__pulse/);
  });
  it("aligns the wide top line and styles one collapsible warning surface", () => {
    const css = readFileSync(stylesPath, "utf8");

    expect(css).toMatch(
      /@media \(min-width: 64rem\)[\s\S]*\.shell-layout__workspace > \.stage-card\s*\{[^}]*padding-top:\s*0/s,
    );
    expect(css).toMatch(
      /\.security-note\s*\{[^}]*grid-template-columns:\s*auto minmax\(0, 1fr\) auto[^}]*cursor:\s*pointer/s,
    );
    expect(css).toMatch(/\.security-note\[data-state="collapsed"\]/);
    expect(css).toMatch(/\.security-note__detail\[hidden\]\s*\{[^}]*display:\s*none/s);
    expect(css).toMatch(/\.security-note:focus-visible/);
    expect(css).not.toContain(".connection-help");
    expect(css).not.toContain(".security-note__dismiss");
  });
  it("uses the approved Midnight and Porcelain palette anchors", () => {
    const css = readFileSync(stylesPath, "utf8");
    const root = css.match(/:root\s*\{(?<body>.*?)\n\}/s)?.groups?.body ?? "";
    const dark =
      css.match(/:root\[data-theme="dark"\]\s*\{(?<body>.*?)\n\}/s)
        ?.groups?.body ?? "";

    expect(root).toContain("--color-bg-canvas: #f4f6fa");
    expect(root).toContain("--color-bg-surface: #ffffff");
    expect(root).toContain("--color-fg-primary: #121826");
    expect(root).toContain("--color-action-primary: #586beb");
    expect(root).toContain("--color-status-success: #168560");
    expect(root).toContain("--color-status-warning: #a86b16");
    expect(root).toContain("--color-status-error: #cf4a5a");

    expect(dark).toContain("--color-bg-canvas: #080b12");
    expect(dark).toContain("--color-bg-surface: #0f1520");
    expect(dark).toContain("--color-bg-subtle: #171f2e");
    expect(dark).toContain("--color-fg-primary: #f4f7fb");
    expect(dark).toContain("--color-action-primary: #7180ff");
    expect(dark).toContain("--color-status-success: #45d39d");
    expect(dark).toContain("--color-status-warning: #e8b45b");
    expect(dark).toContain("--color-status-error: #f07178");
  });
});
