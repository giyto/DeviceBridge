// @vitest-environment jsdom

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createTextTransferView } from "../src/textTransferView";

beforeEach(() => {
  document.open();
  document.write(readFileSync(resolve(process.cwd(), "index.html"), "utf8"));
  document.close();
});

describe("createTextTransferView", () => {
  it("shows an accessible text form only for an active session without controlling file state", () => {
    const actions = createActions();
    const view = createTextTransferView(document, actions);
    const section = document.querySelector<HTMLElement>('[data-role="text-transfer"]')!;

    view.render({ kind: "inactive" });
    expect(section.hidden).toBe(true);

    view.render(activeState());
    expect(section.hidden).toBe(false);
    expect(document.querySelector('label[for="text-draft"]')?.textContent).toContain("Текст");
    expect(document.querySelector<HTMLTextAreaElement>("#text-draft")?.disabled).toBe(false);
    expect(document.querySelector('[data-role="text-feed"]')?.getAttribute("aria-live")).toBe(
      "polite",
    );
    expect(document.querySelector<HTMLElement>('[data-role="file-transfer"]')?.hidden).toBe(true);
    expect(document.querySelector('[data-action="send-file"]')).toBeNull();
  });

  it("supports keyboard form flow and renders sender, time, direction and status", () => {
    const actions = createActions();
    const view = createTextTransferView(document, actions);
    view.render(activeState({
      draft: "Привет",
      items: [{
        messageId: "incoming-1",
        timestamp: Date.UTC(2026, 8, 16, 10, 30),
        content: "Сообщение с телефона",
        contentKind: "TEXT",
        direction: "ANDROID_TO_BROWSER",
        senderLabel: "Pixel 8",
        status: "DELIVERED",
      }],
    }));
    const input = document.querySelector<HTMLTextAreaElement>("#text-draft")!;
    input.value = "Новый текст";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    document.querySelector<HTMLFormElement>('[data-role="text-form"]')
      ?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));

    expect(actions.onDraftChange).toHaveBeenCalledWith("Новый текст");
    expect(actions.onSend).toHaveBeenCalledOnce();
    const card = document.querySelector('[data-message-id="incoming-1"]')!;
    expect(card.textContent).toContain("Pixel 8");
    expect(card.textContent).toContain("С телефона");
    expect(card.textContent).toContain("Доставлено");
    expect(card.querySelector("time")?.dateTime).toBe("2026-09-16T10:30:00.000Z");
    expect(card.textContent).toContain("Сообщение с телефона");
  });

  it("exposes retry for failed items and disables send while a request is active", () => {
    const actions = createActions();
    const view = createTextTransferView(document, actions);
    view.render(activeState({
      draft: "Повтор",
      sending: true,
      items: [{
        messageId: "failed-1",
        timestamp: 1_000,
        content: "Повтор",
        contentKind: "TEXT",
        direction: "BROWSER_TO_ANDROID",
        senderLabel: "Этот браузер",
        status: "FAILED",
      }],
      error: {
        code: "SESSION_UNAVAILABLE",
        message: "Получатель недоступен",
        relatedMessageId: "failed-1",
      },
    }));

    expect(document.querySelector<HTMLButtonElement>('[data-action="send-text"]')?.disabled)
      .toBe(true);
    expect(document.querySelector('[data-role="text-error"]')?.textContent)
      .toContain("Получатель недоступен");
    document.querySelector<HTMLButtonElement>('[data-retry-message-id="failed-1"]')?.click();
    expect(actions.onRetry).toHaveBeenCalledWith("failed-1");
  });

  it("renders HTML-like content and sender labels only as plain text", () => {
    const view = createTextTransferView(document, createActions());
    const payload = '<img src=x onerror="globalThis.pwned=true"><script>alert(1)</script>';
    const senderLabel = "<b>Небезопасный браузер</b>";
    view.render(activeState({
      items: [{
        messageId: "xss-1",
        timestamp: 1_000,
        content: payload,
        contentKind: "TEXT",
        direction: "ANDROID_TO_BROWSER",
        senderLabel,
        status: "DELIVERED",
      }],
    }));

    const card = document.querySelector('[data-message-id="xss-1"]')!;
    expect(card.querySelector(".text-card__content")?.textContent).toBe(payload);
    expect(card.querySelector(".text-card__metadata strong")?.textContent).toBe(senderLabel);
    expect(card.querySelector("img")).toBeNull();
    expect(card.querySelector("script")).toBeNull();
    expect(card.querySelector("b")).toBeNull();
  });

  it("copies only on click and exposes a selectable manual fallback on failure", async () => {
    const clipboard = { write: vi.fn().mockResolvedValue(false) };
    const view = createTextTransferView(document, createActions(), clipboard);
    view.render(activeState({
      items: [{
        messageId: "copy-1",
        timestamp: 1_000,
        content: "Скопировать меня",
        contentKind: "TEXT",
        direction: "ANDROID_TO_BROWSER",
        senderLabel: "Телефон",
        status: "DELIVERED",
      }],
    }));
    expect(clipboard.write).not.toHaveBeenCalled();

    document.querySelector<HTMLButtonElement>('[data-copy-message-id="copy-1"]')?.click();
    await vi.waitFor(() => expect(clipboard.write).toHaveBeenCalledWith("Скопировать меня"));

    const card = document.querySelector('[data-message-id="copy-1"]')!;
    const fallback = card.querySelector<HTMLElement>('[data-role="manual-copy"]')!;
    expect(fallback.hidden).toBe(false);
    expect(fallback.querySelector<HTMLTextAreaElement>("textarea")?.value).toBe(
      "Скопировать меня",
    );
    expect(card.querySelector('[data-role="copy-status"]')?.textContent)
      .not.toContain("Скопировано");
  });

  it("never auto-opens content and exposes open only for a safe server-classified link", () => {
    const opener = { open: vi.fn().mockReturnValue(true) };
    const view = createTextTransferView(
      document,
      createActions(),
      { write: vi.fn().mockResolvedValue(true) },
      opener,
    );
    view.render(activeState({
      items: [
        {
          ...incomingItem("safe-link", "https://example.com/path"),
          contentKind: "LINK",
        },
        {
          ...incomingItem("dangerous-link", "javascript:alert(1)"),
          contentKind: "LINK",
        },
        incomingItem("ordinary-text", "обычный текст"),
      ],
    }));

    expect(opener.open).not.toHaveBeenCalled();
    expect(document.querySelector('[data-open-message-id="safe-link"]')).not.toBeNull();
    expect(document.querySelector('[data-open-message-id="dangerous-link"]')).toBeNull();
    expect(document.querySelector('[data-open-message-id="ordinary-text"]')).toBeNull();

    document.querySelector<HTMLButtonElement>('[data-open-message-id="safe-link"]')?.click();
    expect(opener.open).toHaveBeenCalledWith("https://example.com/path", "LINK");
  });
});

function createActions() {
  return {
    onDraftChange: vi.fn(),
    onSend: vi.fn(),
    onRetry: vi.fn(),
  };
}

function activeState(overrides: Record<string, unknown> = {}) {
  return {
    kind: "active" as const,
    draft: "",
    sending: false,
    items: [],
    error: undefined,
    ...overrides,
  };
}

function incomingItem(messageId: string, content: string) {
  return {
    messageId,
    timestamp: 2_000,
    content,
    contentKind: "TEXT" as const,
    direction: "ANDROID_TO_BROWSER" as const,
    senderLabel: "Телефон",
    status: "DELIVERED" as const,
  };
}
