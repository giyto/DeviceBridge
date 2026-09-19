// @vitest-environment jsdom

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it } from "vitest";

beforeEach(() => {
  document.open();
  document.write(readFileSync(resolve(process.cwd(), "index.html"), "utf8"));
  document.close();
});

describe("adaptive shell structure", () => {
  it("keeps connection content before workspace content in one stable DOM order", () => {
    const layout = document.querySelector<HTMLElement>(".shell-layout")!;
    const connection = layout.querySelector<HTMLElement>(".shell-layout__connection")!;
    const workspace = layout.querySelector<HTMLElement>(".shell-layout__workspace")!;
    const status = document.querySelector<HTMLElement>('[data-role="status"]')!;
    const warning = document.querySelector<HTMLElement>('[data-role="security-warning"]')!;
    const session = document.querySelector<HTMLElement>('[data-role="session-panel"]')!;

    expect(layout).not.toBeNull();
    expect(connection.contains(status)).toBe(true);
    expect(connection.contains(warning)).toBe(true);
    expect(workspace.contains(session)).toBe(true);
    expect(connection.compareDocumentPosition(workspace) & Node.DOCUMENT_POSITION_FOLLOWING)
      .not.toBe(0);
    expect(status.compareDocumentPosition(warning) & Node.DOCUMENT_POSITION_FOLLOWING)
      .not.toBe(0);
  });
  it("removes the persistent header and keeps product and theme controls in connection content", () => {
    const connection = document.querySelector<HTMLElement>('[data-role="connection-area"]')!;
    const title = document.querySelector<HTMLElement>("#page-title")!;
    const themeControl = document.querySelector<HTMLElement>('[data-role="theme-control"]')!;

    expect(document.querySelector(".site-header")).toBeNull();
    expect(connection).not.toBeNull();
    expect(connection.contains(title)).toBe(true);
    expect(connection.contains(themeControl)).toBe(true);
    expect(title.textContent).toContain("DeviceBridge");
    expect(document.querySelector(".eyebrow")).toBeNull();
    expect(document.querySelector(".theme-control-label")).toBeNull();
    expect(document.querySelectorAll('[data-role="theme-control"]')).toHaveLength(1);
    expect(themeControl.tagName).toBe("BUTTON");
  });

  it("uses one native collapsible warning without a separate security help surface", () => {
    const warning = document.querySelector<HTMLButtonElement>(
      '[data-role="security-warning"]',
    )!;

    expect(warning.tagName).toBe("BUTTON");
    expect(warning.type).toBe("button");
    expect(warning.getAttribute("aria-expanded")).toBe("true");
    expect(warning.getAttribute("aria-controls")).toBe("security-warning-detail");
    expect(document.querySelector("#security-warning-detail")).not.toBeNull();
    expect(document.querySelector(".connection-help")).toBeNull();
    expect(document.querySelector('[data-action="hide-security-warning"]')).toBeNull();
    expect(document.querySelector('[data-action="show-security-warning"]')).toBeNull();
  });
});
