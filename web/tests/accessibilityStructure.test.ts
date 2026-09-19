// @vitest-environment jsdom

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it } from "vitest";

beforeEach(() => {
  document.open();
  document.write(readFileSync(resolve(process.cwd(), "index.html"), "utf8"));
  document.close();
});

describe("web accessibility structure", () => {
  it("uses stable landmarks, a valid heading hierarchy and sibling workspaces", () => {
    expect(document.querySelectorAll("header")).toHaveLength(1);
    expect(document.querySelectorAll("main")).toHaveLength(1);
    expect(document.querySelectorAll("footer")).toHaveLength(1);
    expect(document.querySelectorAll("h1")).toHaveLength(1);

    const session = document.querySelector<HTMLElement>('[data-role="session-panel"]')!;
    const text = document.querySelector<HTMLElement>('[data-role="text-transfer"]')!;
    const files = document.querySelector<HTMLElement>('[data-role="file-transfer"]')!;
    expect(text.parentElement).toBe(session);
    expect(files.parentElement).toBe(session);
    expect(text.querySelector("h3#text-transfer-title")).not.toBeNull();
    expect(files.querySelector("h3#file-transfer-title")).not.toBeNull();
    expect(text.querySelector("h4")).not.toBeNull();
    expect(files.querySelector("h4")).not.toBeNull();
  });

  it("uses dedicated polite status regions and assertive validation alerts", () => {
    const connection = document.querySelector('[data-role="status"]');
    const textAnnouncer = document.querySelector('[data-role="text-announcer"]');
    const fileAnnouncer = document.querySelector('[data-role="file-announcer"]');
    expect(connection).toMatchObject({ role: "status", ariaLive: "polite" });
    expect(textAnnouncer).toMatchObject({ ariaLive: "polite" });
    expect(fileAnnouncer).toMatchObject({ ariaLive: "polite" });
    expect(document.querySelector('[data-role="text-feed"]')?.hasAttribute("aria-live"))
      .toBe(false);
    expect(document.querySelector('[data-role="text-error"]')?.getAttribute("role"))
      .toBe("alert");
    expect(document.querySelector('[data-role="file-error"]')?.getAttribute("role"))
      .toBe("alert");
  });

  it("keeps actions native and the keyboard file picker explicitly named", () => {
    for (const action of document.querySelectorAll("[data-action]")) {
      expect(action.tagName, action.getAttribute("data-action") ?? "action").toBe("BUTTON");
    }
    const dropZone = document.querySelector('[data-role="file-drop-zone"]');
    expect(dropZone?.getAttribute("role")).toBe("button");
    expect(dropZone?.getAttribute("aria-controls")).toBe("file-input");
    expect(document.querySelectorAll('[tabindex]:not([tabindex="0"]):not([tabindex="-1"])'))
      .toHaveLength(0);
  });
});
