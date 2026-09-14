// @vitest-environment jsdom

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createShellView } from "../src/shellView";

beforeEach(() => {
  document.open();
  document.write(readFileSync(resolve(process.cwd(), "index.html"), "utf8"));
  document.close();
});

describe("DeviceBridge shell markup", () => {
  it("uses semantic landmarks and a textual live status", () => {
    createShellView(document, () => undefined);

    expect(document.querySelector("header")).not.toBeNull();
    expect(document.querySelector("main")).not.toBeNull();
    expect(document.querySelector("footer")).not.toBeNull();
    expect(document.querySelector("h1")?.textContent).toContain("DeviceBridge");
    expect(document.querySelector('[data-role="status"]')).toMatchObject({
      ariaLive: "polite",
      role: "status",
    });
    expect(document.querySelector('[data-role="status-title"]')?.textContent).toContain(
      "Проверяем",
    );
  });

  it("shows the trusted-network HTTP warning without technical euphemisms", () => {
    createShellView(document, () => undefined);
    const warning = document.querySelector('[data-role="security-warning"]')?.textContent ?? "";

    expect(warning).toContain("HTTP");
    expect(warning).toContain("не шифрует");
    expect(warning).toContain("доверенной");
    expect(warning).toContain("публичном Wi-Fi");
  });

  it("does not expose unfinished pairing, text or file controls", () => {
    createShellView(document, () => undefined);

    expect(document.querySelector("form")).toBeNull();
    expect(document.querySelector("input")).toBeNull();
    expect(document.querySelector('button[data-action="pair"]')).toBeNull();
    expect(document.querySelector('button[data-action="send-text"]')).toBeNull();
    expect(document.querySelector('button[data-action="send-file"]')).toBeNull();
    expect(document.body.textContent).toContain("Подключение и передача появятся позже");
  });
});

describe("createShellView", () => {
  it("renders compatible availability and exact version data", () => {
    const view = createShellView(document, () => undefined);

    view.render({
      kind: "available",
      manifest: { protocolVersion: 1, webAssetVersion: "sha256-abcd" },
    });

    expect(document.querySelector('[data-role="status-title"]')?.textContent).toBe(
      "DeviceBridge доступен",
    );
    expect(document.querySelector('[data-role="protocol-version"]')?.textContent).toBe("1");
    expect(document.querySelector('[data-role="asset-version"]')?.textContent).toBe(
      "sha256-abcd",
    );
    expect(document.querySelector<HTMLButtonElement>('[data-action="retry"]')?.hidden).toBe(
      true,
    );
  });

  it("renders an unavailable state and invokes manual retry", () => {
    const retry = vi.fn();
    const view = createShellView(document, retry);

    view.render({
      kind: "unavailable",
      message: "Не удаётся связаться с DeviceBridge.",
    });
    document.querySelector<HTMLButtonElement>('[data-action="retry"]')?.click();

    expect(document.querySelector('[data-role="status-title"]')?.textContent).toBe(
      "Связь потеряна",
    );
    expect(document.querySelector('[data-role="status-detail"]')?.textContent).toContain(
      "Не удаётся",
    );
    expect(document.querySelector<HTMLButtonElement>('[data-action="retry"]')?.hidden).toBe(
      false,
    );
    expect(retry).toHaveBeenCalledOnce();
  });

  it("explains the next automatic retry", () => {
    const view = createShellView(document, () => undefined);

    view.render({
      kind: "unavailable",
      message: "Не удаётся связаться с DeviceBridge.",
      nextRetryInMs: 2_000,
    });

    expect(document.querySelector('[data-role="status-detail"]')?.textContent).toContain(
      "2 сек",
    );
  });
});
