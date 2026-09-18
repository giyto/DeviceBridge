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
    createShellView(document, actions());

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
    createShellView(document, actions());
    const warning = document.querySelector('[data-role="security-warning"]')?.textContent ?? "";

    expect(warning).toContain("HTTP");
    expect(warning).toContain("не шифрует");
    expect(warning).toContain("доверенной");
    expect(warning).toContain("публичном Wi-Fi");
  });

  it("exposes an accessible pairing form while transfer sections wait for a session", () => {
    createShellView(document, actions());

    expect(document.querySelector('label[for="pairing-code"]')?.textContent).toContain("код");
    expect(document.querySelector<HTMLInputElement>("#pairing-code")?.inputMode).toBe("numeric");
    expect(document.querySelector<HTMLButtonElement>('button[data-action="pair"]')).not.toBeNull();
    expect(document.querySelector('label[for="remember-browser"]')?.textContent).toContain(
      "Запомнить этот браузер",
    );
    expect(document.querySelector<HTMLButtonElement>('button[data-action="send-text"]')?.disabled).toBe(true);
    expect(document.querySelector<HTMLElement>('[data-role="text-transfer"]')?.hidden).toBe(true);
    expect(document.querySelector<HTMLElement>('[data-role="file-transfer"]')?.hidden).toBe(true);
    expect(document.querySelector('label[for="file-input"]')?.textContent).toContain("Выберите файлы");
  });

  it("submits the explicit remember-browser choice", () => {
    const onSubmitCode = vi.fn();
    const view = createShellView(document, { ...actions(), onSubmitCode });
    view.render({
      kind: "ready",
      manifest: { protocolVersion: 1, webAssetVersion: "sha256-abcd" },
      challenge: {
        protocolVersion: 1,
        challengeId: "challenge-remember",
        expiresAtEpochMillis: 10_000,
        confirmTimeoutSeconds: 60,
        attemptsRemaining: 5,
      },
    });
    const input = document.querySelector<HTMLInputElement>("#pairing-code")!;
    const remember = document.querySelector<HTMLInputElement>("#remember-browser")!;
    input.value = "123456";
    remember.checked = true;

    document.querySelector<HTMLFormElement>('[data-role="pairing-form"]')
      ?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));

    expect(onSubmitCode).toHaveBeenCalledWith("123456", true);
  });
});

describe("createShellView", () => {
  it("renders compatible availability and exact version data", () => {
    const view = createShellView(document, actions());

    view.render({
      kind: "ready",
      manifest: { protocolVersion: 1, webAssetVersion: "sha256-abcd" },
      challenge: {
        protocolVersion: 1,
        challengeId: "challenge-1",
        expiresAtEpochMillis: 10_000,
        confirmTimeoutSeconds: 60,
        attemptsRemaining: 5,
      },
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
    expect(document.querySelector<HTMLFormElement>('[data-role="pairing-form"]')?.hidden).toBe(false);
  });

  it("renders an offline state and invokes manual retry", () => {
    const retry = vi.fn();
    const view = createShellView(document, { ...actions(), onRetry: retry });

    view.render({
      kind: "offline",
      message: "Не удаётся связаться с DeviceBridge.",
    });
    document.querySelector<HTMLButtonElement>('[data-action="retry"]')?.click();

    expect(document.querySelector('[data-role="status-title"]')?.textContent).toBe(
      "DeviceBridge недоступен",
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
    const view = createShellView(document, actions());

    view.render({
      kind: "offline",
      message: "Не удаётся связаться с DeviceBridge.",
      nextRetryInMs: 2_000,
    });

    expect(document.querySelector('[data-role="status-detail"]')?.textContent).toContain(
      "2 сек",
    );
  });

  it("submits six digits, renders waiting and disconnects a connected session", () => {
    const onSubmitCode = vi.fn();
    const onDisconnect = vi.fn();
    const view = createShellView(document, {
      ...actions(),
      onSubmitCode,
      onDisconnect,
    });
    const manifest = { protocolVersion: 1, webAssetVersion: "sha256-abcd" };
    view.render({
      kind: "ready",
      manifest,
      challenge: {
        protocolVersion: 1,
        challengeId: "challenge-1",
        expiresAtEpochMillis: 10_000,
        confirmTimeoutSeconds: 60,
        attemptsRemaining: 5,
      },
    });
    const input = document.querySelector<HTMLInputElement>("#pairing-code")!;
    input.value = "123456";
    document.querySelector<HTMLFormElement>('[data-role="pairing-form"]')
      ?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    expect(onSubmitCode).toHaveBeenCalledWith("123456", false);

    view.render({ kind: "awaiting", manifest });
    expect(document.querySelector('[data-role="session-title"]')?.textContent).toContain(
      "Подтвердите",
    );
    expect(input.disabled).toBe(true);

    view.render({
      kind: "connected",
      manifest,
      status: {
        protocolVersion: 1,
        sessionId: "session-1",
        connected: true,
        activeSessionCount: 2,
        effectiveFileLimitBytes: 1_073_741_824,
      },
    });
    document.querySelector<HTMLButtonElement>('[data-action="disconnect"]')?.click();
    expect(onDisconnect).toHaveBeenCalledOnce();
    expect(document.body.textContent).not.toContain("session-1");
  });
});

function actions() {
  return {
    onRetry: () => undefined,
    onSubmitCode: (_code: string, _rememberBrowser: boolean) => undefined,
    onDisconnect: () => undefined,
  };
}
