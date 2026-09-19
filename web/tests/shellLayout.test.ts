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
});
