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

    expect(document.querySelector("header")).toBeNull();
    expect(document.querySelector("main")).not.toBeNull();
    expect(document.querySelector("footer")).not.toBeNull();
    expect(document.querySelector("h1")?.textContent).toContain("DeviceBridge");
    expect(document.querySelector('[data-role="connection-area"]')?.contains(document.querySelector("h1"))).toBe(true);
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
    expect(warning.replace(/\s+/g, " ")).toContain("другое устройство может выдать себя за телефон");
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
  it("shows the wait for the phone and why the form is back", () => {
    const onRetry = vi.fn();
    const view = createShellView(document, { ...actions(), onRetry });
    const title = document.querySelector('[data-role="status-title"]')!;
    const detail = document.querySelector('[data-role="status-detail"]')!;
    const retry = document.querySelector<HTMLButtonElement>('[data-action="retry"]')!;
    const manifest = { protocolVersion: 1, webAssetVersion: "sha256-abcd" };
    const challenge = {
      protocolVersion: 1,
      challengeId: "challenge-notice",
      expiresAtEpochMillis: 10_000,
      confirmTimeoutSeconds: 60,
      attemptsRemaining: 5,
    };

    view.render({ kind: "waiting" });
    expect(title.textContent).toBe("Телефон недоступен");
    expect(detail.textContent).toContain("подключится сама");
    expect(retry.hidden).toBe(false);
    expect(retry.textContent).toBe("Проверить сейчас");
    retry.click();
    expect(onRetry).toHaveBeenCalledOnce();

    view.render({ kind: "ready", manifest, challenge, notice: "phoneReturned" });
    expect(title.textContent).toBe("Телефон снова доступен");

    view.render({ kind: "ready", manifest, challenge, notice: "trustRejected" });
    expect(detail.textContent).toContain("Этот телефон не узнал браузер");
    expect(detail.textContent).toContain("удалили из доверенных");
    expect(detail.textContent).toContain("другой телефон");
  });

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
    expect(document.querySelector('[data-role="protocol-version"]')).toBeNull();
    expect(document.querySelector<HTMLElement>('[data-role="version-data"]')?.hidden).toBe(true);
    expect(document.body.textContent).not.toContain("sha256-abcd");
    expect(document.querySelector<HTMLElement>('[data-role="device-row"]')?.hidden).toBe(true);
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
    // "Запомнить этот браузер" is on unless the person turns it off.
    expect(onSubmitCode).toHaveBeenCalledWith("123456", true);
    document.querySelector<HTMLInputElement>("#remember-browser")!.checked = false;
    document.querySelector<HTMLFormElement>('[data-role="pairing-form"]')
      ?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    expect(onSubmitCode).toHaveBeenLastCalledWith("123456", false);

    view.render({ kind: "submitting", manifest });
    expect(document.querySelector<HTMLButtonElement>('[data-action="pair"]')?.textContent)
      .toContain("Проверяем");
    expect(document.querySelector<HTMLButtonElement>('[data-action="pair"]')?.disabled).toBe(true);

    view.render({ kind: "awaiting", manifest });
    expect(document.querySelector<HTMLButtonElement>('[data-action="pair"]')?.textContent)
      .toContain("Ожидаем");
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
        deviceName: "Google Pixel 8",
      },
    });
    document.querySelector<HTMLButtonElement>('[data-action="disconnect"]')?.click();
    expect(onDisconnect).toHaveBeenCalledOnce();
    expect(document.body.textContent).not.toContain("session-1");
    expect(document.querySelector('[data-role="device-name"]')?.textContent).toBe(
      "Google Pixel 8",
    );
    expect(document.querySelector<HTMLElement>('[data-role="device-row"]')?.hidden).toBe(false);
    expect(document.querySelector('[data-role="session-count"]')?.textContent).toBe("2");
    expect(document.querySelector<HTMLElement>('[data-role="session-count-row"]')?.hidden)
      .toBe(false);
    expect(document.querySelector<HTMLElement>('[data-role="session-heading"]')?.hidden)
      .toBe(true);
    expect(
      document.querySelector('[data-role="status"]')
        ?.contains(document.querySelector('[data-action="disconnect"]')),
    ).toBe(true);
  });

  it("shows automatic reconnect progress without offering a competing manual retry", () => {
    const view = createShellView(document, actions());

    view.render({
      kind: "reconnecting",
      manifest: { protocolVersion: 1, webAssetVersion: "sha256-abcd" },
      status: {
        protocolVersion: 1,
        sessionId: "session-1",
        connected: true,
        activeSessionCount: 1,
        effectiveFileLimitBytes: 1_073_741_824,
        deviceName: "Google Pixel 8",
      },
      attempt: 2,
      nextRetryInMs: 2_000,
    });

    expect(document.querySelector('[data-role="status-title"]')?.textContent).toContain(
      "Восстанавливаем",
    );
    expect(document.querySelector('[data-role="status-detail"]')?.textContent).toContain(
      "2 сек",
    );
    expect(document.querySelector<HTMLButtonElement>('[data-action="retry"]')?.hidden).toBe(true);
  });

  it("offers an explicit retry after automatic reconnect is exhausted", () => {
    const retry = vi.fn();
    const view = createShellView(document, { ...actions(), onRetry: retry });

    view.render({
      kind: "needsUserAction",
      manifest: { protocolVersion: 1, webAssetVersion: "sha256-abcd" },
      status: {
        protocolVersion: 1,
        sessionId: "session-1",
        connected: true,
        activeSessionCount: 1,
        effectiveFileLimitBytes: 1_073_741_824,
        deviceName: "Google Pixel 8",
      },
      message: "Проверьте сеть и повторите попытку.",
    });

    const retryButton = document.querySelector<HTMLButtonElement>('[data-action="retry"]')!;
    expect(document.querySelector('[data-role="status-title"]')?.textContent).toContain(
      "Нужно проверить подключение",
    );
    expect(retryButton.textContent).toContain("Проверить подключение");
    expect(retryButton.hidden).toBe(false);
    retryButton.click();
    expect(retry).toHaveBeenCalledOnce();
  });

  it("renders uncertain pairing as a status check instead of a new submission", () => {
    const retry = vi.fn();
    const view = createShellView(document, { ...actions(), onRetry: retry });

    view.render({
      kind: "uncertain",
      manifest: { protocolVersion: 1, webAssetVersion: "sha256-abcd" },
      message: "Ответ о подключении не получен.",
      checking: false,
    });

    const retryButton = document.querySelector<HTMLButtonElement>('[data-action="retry"]')!;
    expect(document.querySelector('[data-role="status-title"]')?.textContent).toContain(
      "Результат подключения неизвестен",
    );
    expect(retryButton.textContent).toContain("Проверить результат");
    expect(retryButton.hidden).toBe(false);
    retryButton.click();
    expect(retry).toHaveBeenCalledOnce();
  });
  it("restores focus to the code after a pairing error", () => {
    const view = createShellView(document, actions());
    const manifest = { protocolVersion: 1, webAssetVersion: "sha256-abcd" };
    const challenge = {
      protocolVersion: 1,
      challengeId: "challenge-focus",
      expiresAtEpochMillis: 10_000,
      confirmTimeoutSeconds: 60,
      attemptsRemaining: 5,
    };
    const input = document.querySelector<HTMLInputElement>("#pairing-code")!;

    view.render({ kind: "ready", manifest, challenge });
    input.focus();
    view.render({ kind: "submitting", manifest });
    document.body.tabIndex = -1;
    document.body.focus();
    view.render({ kind: "ready", manifest, challenge: { ...challenge, attemptsRemaining: 4 } });

    expect(document.activeElement).toBe(input);
  });

  it("restores focus after a manual retry finishes", () => {
    const retry = vi.fn();
    const view = createShellView(document, { ...actions(), onRetry: retry });
    const button = document.querySelector<HTMLButtonElement>('[data-action="retry"]')!;

    view.render({ kind: "offline", message: "Нет связи" });
    button.focus();
    button.click();
    view.render({ kind: "checking" });
    document.body.tabIndex = -1;
    document.body.focus();
    view.render({ kind: "offline", message: "Связь всё ещё недоступна" });

    expect(retry).toHaveBeenCalledOnce();
    expect(document.activeElement).toBe(button);
  });
  it("exposes explicit presentation states and consumes the pairing reset once per session", () => {
    const callbacks = {
      onRetry: vi.fn(),
      onSubmitCode: vi.fn(),
      onDisconnect: vi.fn(),
    };
    const view = createShellView(document, callbacks);
    const status = document.querySelector<HTMLElement>('[data-role="status"]')!;
    const sessionPanel = document.querySelector<HTMLElement>('[data-role="session-panel"]')!;
    const input = document.querySelector<HTMLInputElement>("#pairing-code")!;
    const remember = document.querySelector<HTMLInputElement>("#remember-browser")!;
    const manifest = { protocolVersion: 1, webAssetVersion: "sha256-abcd" };
    const connected = {
      kind: "connected" as const,
      manifest,
      status: {
        protocolVersion: 1,
        sessionId: "session-effect-1",
        connected: true,
        activeSessionCount: 1,
        effectiveFileLimitBytes: 1_073_741_824,
        deviceName: "Google Pixel 8",
      },
    };

    view.render({
      kind: "ready",
      manifest,
      challenge: {
        protocolVersion: 1,
        challengeId: "challenge-state",
        expiresAtEpochMillis: 10_000,
        confirmTimeoutSeconds: 60,
        attemptsRemaining: 5,
      },
    });
    expect(status.dataset.viewState).toBe("ready");
    expect(sessionPanel.dataset.state).toBe("ready");
    expect(sessionPanel.dataset.viewState).toBe("ready");

    // Checked from the start, and a reset turns it back on.
    expect(remember.checked).toBe(true);
    input.value = "123456";
    remember.checked = false;
    view.consume({ id: "clear-pairing-form:session-effect-1", kind: "clearPairingForm" });
    view.render(connected);
    expect(input.value).toBe("");
    expect(remember.checked).toBe(true);

    input.value = "654321";
    remember.checked = false;
    view.render(connected);
    view.consume({ id: "clear-pairing-form:session-effect-1", kind: "clearPairingForm" });
    expect(input.value).toBe("654321");
    expect(remember.checked).toBe(false);

    view.render({ kind: "offline", message: "Нет связи" });
    expect(status.dataset.viewState).toBe("offline");
    expect(sessionPanel.dataset.viewState).toBe("offline");
    view.render({ kind: "denied", manifest, message: "Отклонено" });
    expect(status.dataset.viewState).toBe("error");
    expect(sessionPanel.dataset.viewState).toBe("error");
    expect(callbacks.onRetry).not.toHaveBeenCalled();
    expect(callbacks.onSubmitCode).not.toHaveBeenCalled();
    expect(callbacks.onDisconnect).not.toHaveBeenCalled();
  });
});

function actions() {
  return {
    onRetry: () => undefined,
    onSubmitCode: (_code: string, _rememberBrowser: boolean) => undefined,
    onDisconnect: () => undefined,
  };
}
