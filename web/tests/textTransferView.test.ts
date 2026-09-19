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
    expect(document.querySelector('[data-role="text-feed"]')?.hasAttribute("aria-live")).toBe(false);
    expect(document.querySelector('[data-role="text-announcer"]')?.getAttribute("aria-live"))
      .toBe("polite");
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
        retryable: true,
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

  it("restores focus inside the message after retry removes its button", () => {
    const actions = createActions();
    const view = createTextTransferView(document, actions);
    const failed = {
      messageId: "retry-focus",
      timestamp: 1_000,
      content: "Повтор",
      contentKind: "TEXT" as const,
      direction: "BROWSER_TO_ANDROID" as const,
      senderLabel: "Этот браузер",
      status: "FAILED" as const,
      retryable: true,
    };
    view.render(activeState({ items: [failed] }));
    const retry = document.querySelector<HTMLButtonElement>('[data-retry-message-id="retry-focus"]')!;
    retry.focus();
    retry.click();

    view.render(activeState({ items: [{ ...failed, status: "SENDING", retryable: false }] }));

    expect(document.activeElement).toBe(
      document.querySelector<HTMLButtonElement>('[data-copy-message-id="retry-focus"]'),
    );
  });
  it("keeps the draft editable but blocks send and retry while reconnecting", () => {
    const view = createTextTransferView(document, createActions());
    view.render(activeState({
      connectionAvailable: false,
      draft: "Черновик остаётся",
      items: [{
        messageId: "failed-offline",
        timestamp: 1_000,
        content: "Повтор",
        contentKind: "TEXT",
        direction: "BROWSER_TO_ANDROID",
        senderLabel: "Этот браузер",
        status: "FAILED",
        retryable: true,
      }],
    }));

    expect(document.querySelector<HTMLTextAreaElement>("#text-draft")?.disabled).toBe(false);
    expect(document.querySelector<HTMLTextAreaElement>("#text-draft")?.value).toBe("Черновик остаётся");
    expect(document.querySelector<HTMLButtonElement>('[data-action="send-text"]')?.disabled).toBe(true);
    expect(document.querySelector<HTMLButtonElement>('[data-retry-message-id="failed-offline"]')?.disabled).toBe(true);
  });

  it("does not offer retry for an unchanged terminally invalid payload", () => {
    const view = createTextTransferView(document, createActions());
    view.render(activeState({
      items: [{
        messageId: "oversized-1",
        timestamp: 1_000,
        content: "Слишком большой текст",
        contentKind: "TEXT",
        direction: "BROWSER_TO_ANDROID",
        senderLabel: "Этот браузер",
        status: "FAILED",
        retryable: false,
      }],
      error: {
        code: "CONTENT_TOO_LARGE",
        message: "Текст превышает лимит 100 КБ.",
        relatedMessageId: "oversized-1",
      },
    }));

    expect(document.querySelector('[data-retry-message-id="oversized-1"]')).toBeNull();
    expect(document.querySelector('[data-role="text-error"]')?.textContent)
      .toContain("100 КБ");
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

  it("groups link actions in a responsive row inside the card", () => {
    const view = createTextTransferView(document, createActions());
    view.render(activeState({
      items: [{
        ...incomingItem("responsive-link", "https://www.youtube.com/watch?v=example"),
        contentKind: "LINK",
      }],
    }));

    const card = document.querySelector('[data-message-id="responsive-link"]')!;
    const actionRow = card.querySelector(".text-card__actions");
    const open = card.querySelector('[data-open-message-id="responsive-link"]');
    const copy = card.querySelector('[data-copy-message-id="responsive-link"]');

    expect(actionRow).not.toBeNull();
    expect(open?.parentElement).toBe(actionRow);
    expect(copy?.parentElement).toBe(actionRow);
  });
  it("renders explicit presentation states without dispatching transfer commands", () => {
    const callbacks = createActions();
    const view = createTextTransferView(document, callbacks);
    const section = document.querySelector<HTMLElement>('[data-role="text-transfer"]')!;

    view.render({ kind: "inactive" });
    expect(section.dataset.viewState).toBe("disabled");
    view.render(activeState());
    expect(section.dataset.viewState).toBe("empty");
    view.render(activeState({ draft: "Готово" }));
    expect(section.dataset.viewState).toBe("ready");
    view.render(activeState({ draft: "Отправка", sending: true }));
    expect(section.dataset.viewState).toBe("loading");
    view.render(activeState({ connectionAvailable: false, draft: "Черновик" }));
    expect(section.dataset.viewState).toBe("offline");
    view.render(activeState({
      error: { code: "SESSION_UNAVAILABLE", message: "Нет получателя" },
    }));
    expect(section.dataset.viewState).toBe("error");

    view.render(activeState({ draft: "Повторный render" }));
    view.render(activeState({ draft: "Повторный render" }));
    expect(callbacks.onDraftChange).not.toHaveBeenCalled();
    expect(callbacks.onSend).not.toHaveBeenCalled();
    expect(callbacks.onRetry).not.toHaveBeenCalled();
  });
  it("keeps focused item actions stable when only delivery status changes", () => {
    const view = createTextTransferView(document, createActions());
    const sending = {
      messageId: "stable-focus",
      timestamp: 2_000,
      content: "https://example.com/very/long/path?value=one-two-three",
      contentKind: "LINK" as const,
      direction: "BROWSER_TO_ANDROID" as const,
      senderLabel: "Этот браузер",
      status: "SENDING" as const,
    };
    view.render(activeState({ items: [sending] }));
    const card = document.querySelector<HTMLElement>('[data-message-id="stable-focus"]')!;
    const copy = card.querySelector<HTMLButtonElement>('[data-copy-message-id="stable-focus"]')!;
    copy.focus();

    view.render(activeState({ items: [{ ...sending, status: "DELIVERED" }] }));

    expect(document.querySelector('[data-message-id="stable-focus"]')).toBe(card);
    expect(document.activeElement).toBe(copy);
    expect(card.querySelector(".text-card__status")?.textContent).toBe("Доставлено");
  });

  it("announces only new messages and real delivery status transitions", () => {
    const view = createTextTransferView(document, createActions());
    const item = {
      messageId: "announcement-1",
      timestamp: 2_000,
      content: "Проверка уведомления",
      contentKind: "TEXT" as const,
      direction: "BROWSER_TO_ANDROID" as const,
      senderLabel: "Этот браузер",
      status: "SENDING" as const,
    };
    const announcer = document.querySelector<HTMLElement>('[data-role="text-announcer"]')!;

    view.render(activeState({ items: [item] }));
    expect(announcer.textContent).toContain("Отправляется");

    announcer.textContent = "sentinel";
    view.render(activeState({ items: [item] }));
    expect(announcer.textContent).toBe("sentinel");

    view.render(activeState({ items: [{ ...item, status: "DELIVERED" }] }));
    expect(announcer.textContent).toContain("Доставлено");
  });
  it("uses one semantic order for direction, status and applicable link actions", () => {
    const view = createTextTransferView(document, createActions());
    view.render(activeState({ items: [{
      messageId: "semantic-link",
      timestamp: 2_000,
      content: "https://example.com/path",
      contentKind: "LINK",
      direction: "BROWSER_TO_ANDROID",
      senderLabel: "Этот браузер",
      status: "FAILED",
      retryable: true,
    }] }));

    const card = document.querySelector<HTMLElement>('[data-message-id="semantic-link"]')!;
    const actions = card.querySelector<HTMLElement>(".text-card__actions")!;
    expect(card.getAttribute("aria-label")).toContain("На телефон");
    expect(card.getAttribute("aria-label")).toContain("Ошибка");
    expect(actions.getAttribute("role")).toBe("group");
    expect(actions.getAttribute("aria-label")).toContain("Действия");
    expect(Array.from(actions.querySelectorAll("button"), (button) => button.textContent)).toEqual([
      "Открыть ссылку",
      "Копировать",
      "Повторить",
    ]);
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
    connectionAvailable: true,
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
