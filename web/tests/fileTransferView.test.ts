// @vitest-environment jsdom

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createFileTransferView } from "../src/fileTransferView";
import type { FileTransferUiState } from "../src/fileTransferController";

beforeEach(() => {
  document.open();
  document.write(readFileSync(resolve(process.cwd(), "index.html"), "utf8"));
  document.close();
});

describe("createFileTransferView", () => {
  it("uses one validation path for multiple input and drag-and-drop without auto-upload", () => {
    const actions = createActions();
    const view = createFileTransferView(document, actions);
    view.render(active());
    const files = [
      new File(["one"], "one.txt"),
      new File(["two"], "two.txt"),
    ];
    const input = document.querySelector<HTMLInputElement>("#file-input")!;
    Object.defineProperty(input, "files", { value: files });
    Object.defineProperty(input, "value", { value: "selected", writable: true });
    input.dispatchEvent(new Event("change", { bubbles: true }));
    expect(actions.onSelect).toHaveBeenCalledWith(files);
    expect(input.value).toBe("");
    expect(actions.onConfirm).not.toHaveBeenCalled();

    const zone = document.querySelector<HTMLElement>('[data-role="file-drop-zone"]')!;
    const drop = new Event("drop", { bubbles: true, cancelable: true }) as DragEvent;
    Object.defineProperty(drop, "dataTransfer", { value: { files } });
    zone.dispatchEvent(drop);
    expect(actions.onSelect).toHaveBeenLastCalledWith(files);
    expect(zone.tabIndex).toBe(0);
  });

  it("exposes accessible remove, clear, add and disabled empty confirm controls", () => {
    const actions = createActions();
    const view = createFileTransferView(document, actions);
    view.render(active());
    expect(document.querySelector<HTMLButtonElement>('[data-action="confirm-files"]')?.disabled)
      .toBe(true);
    expect(document.querySelector('label[for="file-input"]')?.textContent)
      .toContain("Выберите файлы");

    view.render(active({
      selection: [{
        key: "draft-1",
        displayName: "report.txt",
        sizeBytes: 3,
        mimeType: "text/plain",
      }],
    }));
    const remove = document.querySelector<HTMLButtonElement>('[data-action="remove-draft-file"]')!;
    expect(remove.getAttribute("aria-label")).toBe("Удалить report.txt из выбранных");
    remove.click();
    expect(actions.onRemoveDraft).toHaveBeenCalledWith("draft-1");
    const clear = document.querySelector<HTMLButtonElement>('[data-action="clear-file-draft"]')!;
    expect(clear.hidden).toBe(false);
    clear.click();
    expect(actions.onClearDraft).toHaveBeenCalledOnce();
  });

  it("blocks network actions but keeps the selected files visible while reconnecting", () => {
    const view = createFileTransferView(document, createActions());
    view.render(active({
      connectionAvailable: false,
      selection: [{
        key: "draft-1",
        displayName: "draft.txt",
        sizeBytes: 5,
        mimeType: "text/plain",
      }],
      transfers: [item("CONNECTING")],
    }));

    expect(document.body.textContent).toContain("draft.txt");
    expect(document.querySelector<HTMLButtonElement>('[data-action="confirm-files"]')?.disabled).toBe(true);
    expect(document.querySelector<HTMLButtonElement>('[data-action="download-file"]')?.disabled).toBe(true);
    expect(document.querySelector<HTMLButtonElement>('[data-action="cancel-file"]')?.disabled).toBe(true);
  });
  it("renders progress and safe actions without interpreting filenames as HTML", () => {
    const actions = createActions();
    const view = createFileTransferView(document, actions);
    view.render(active({
      transfers: [{
        id: "file-1",
        metadata: {
          transferId: "file-1",
          displayName: '<img src=x onerror="alert(1)">report.bin',
          sizeBytes: 100,
          mimeType: "application/octet-stream",
          sha256: "a".repeat(64),
          direction: "ANDROID_TO_BROWSER",
        },
        status: "TRANSFERRING",
        bytesTransferred: 50,
        speedBytesPerSecond: 10,
      }],
    }));

    const card = document.querySelector('[data-transfer-id="file-1"]')!;
    expect(card.textContent).toContain('<img src=x onerror="alert(1)">report.bin');
    expect(card.querySelector("img")).toBeNull();
    expect(card.textContent).toContain("50%");
    card.querySelector<HTMLButtonElement>('[data-action="cancel-file"]')?.click();
    expect(actions.onCancel).toHaveBeenCalledWith("file-1");
  });

  it("keeps the cancel control connected while upload progress is rendered", () => {
    const actions = createActions();
    const view = createFileTransferView(document, actions);
    view.render(active({ transfers: [{
      ...item("TRANSFERRING"),
      bytesTransferred: 1,
    }] }));
    const cancel = document.querySelector<HTMLButtonElement>(
      '[data-action="cancel-file"]',
    )!;

    view.render(active({ transfers: [{
      ...item("TRANSFERRING"),
      bytesTransferred: 3,
    }] }));

    expect(cancel.isConnected).toBe(true);
    expect(document.querySelector('[data-action="cancel-file"]')).toBe(cancel);
    cancel.click();
    expect(actions.onCancel).toHaveBeenCalledWith("file-1");
  });

  it("exposes download and retry without asking to reselect a downloaded file", () => {
    const actions = createActions();
    const view = createFileTransferView(document, actions);
    view.render(active({ transfers: [item("CONNECTING")] }));
    document.querySelector<HTMLButtonElement>('[data-action="download-file"]')?.click();
    expect(actions.onDownload).toHaveBeenCalledWith("file-1");

    view.render(active({ transfers: [item("VERIFYING")] }));
    expect(document.querySelector('[data-action="verify-file"]')).toBeNull();
    const verifyingProgress = document.querySelector<HTMLProgressElement>("progress")!;
    expect(verifyingProgress.hasAttribute("value")).toBe(false);
    expect(document.querySelector('[data-transfer-id="file-1"]')?.textContent)
      .toContain("Проверяется");
    expect(document.querySelector('[data-transfer-id="file-1"]')?.textContent)
      .not.toContain("100%");
    expect(document.querySelector(".file-card__verification-note")).toBeNull();
    expect(document.querySelector("#file-verification-input")).toBeNull();

    view.render(active({ transfers: [item("COMPLETED")] }));
    expect(document.querySelector('[data-transfer-id="file-1"]')?.textContent)
      .toContain("Завершено");
    expect(document.querySelector('[data-action="cancel-file"]')).toBeNull();

    view.render(active({ transfers: [item("FAILED")] }));
    document.querySelector<HTMLButtonElement>('[data-action="retry-file"]')?.click();
    expect(actions.onRetry).toHaveBeenCalledWith("file-1");
    expect(document.querySelector('[data-role="file-announcer"]')?.textContent)
      .toContain("Ошибка");
  });
  it("moves focus from cancel to retry and back to the active transfer action", () => {
    const actions = createActions();
    const view = createFileTransferView(document, actions);
    view.render(active({ transfers: [item("TRANSFERRING")] }));
    const cancel = document.querySelector<HTMLButtonElement>('[data-action="cancel-file"]')!;
    cancel.focus();
    cancel.click();

    view.render(active({ transfers: [item("CANCELLED")] }));
    const retry = document.querySelector<HTMLButtonElement>('[data-action="retry-file"]')!;
    expect(document.activeElement).toBe(retry);
    retry.click();

    view.render(active({ transfers: [item("CONNECTING")] }));
    expect(document.activeElement).toBe(
      document.querySelector<HTMLButtonElement>('[data-action="cancel-file"]'),
    );
  });
  it("renders explicit presentation states without dispatching file commands", () => {
    const callbacks = createActions();
    const view = createFileTransferView(document, callbacks);
    const section = document.querySelector<HTMLElement>('[data-role="file-transfer"]')!;

    view.render({ kind: "inactive" });
    expect(section.dataset.viewState).toBe("disabled");
    view.render(active());
    expect(section.dataset.viewState).toBe("empty");
    view.render(active({ preparing: true }));
    expect(section.dataset.viewState).toBe("loading");
    view.render(active({ connectionAvailable: false }));
    expect(section.dataset.viewState).toBe("offline");
    view.render(active({ error: "Файл недоступен" }));
    expect(section.dataset.viewState).toBe("error");
    view.render(active({ transfers: [{ ...item("FAILED"), status: "CANCELLED" }] }));
    expect(section.dataset.viewState).toBe("cancelled");
    view.render(active({ transfers: [item("COMPLETED")] }));
    expect(section.dataset.viewState).toBe("ready");

    view.render(active({ selection: [{
      key: "draft-render",
      displayName: "render.txt",
      sizeBytes: 6,
      mimeType: "text/plain",
    }] }));
    view.render(active({ selection: [{
      key: "draft-render",
      displayName: "render.txt",
      sizeBytes: 6,
      mimeType: "text/plain",
    }] }));
    expect(callbacks.onSelect).not.toHaveBeenCalled();
    expect(callbacks.onConfirm).not.toHaveBeenCalled();
    expect(callbacks.onCancel).not.toHaveBeenCalled();
    expect(callbacks.onRetry).not.toHaveBeenCalled();
    expect(callbacks.onDownload).not.toHaveBeenCalled();
  });
  it("uses shared file type, direction, stage and action semantics for long names", () => {
    const view = createFileTransferView(document, createActions());
    const longName = `${"очень-длинное-имя-".repeat(8)}video.mp4`;
    view.render(active({ transfers: [{
      ...item("CONNECTING"),
      metadata: {
        ...item("CONNECTING").metadata,
        displayName: longName,
        mimeType: "video/mp4",
        direction: "ANDROID_TO_BROWSER",
      },
    }] }));

    const card = document.querySelector<HTMLElement>('[data-transfer-id="file-1"]')!;
    const actions = card.querySelector<HTMLElement>(".file-card__actions")!;
    expect(card.getAttribute("aria-label")).toContain("С телефона");
    expect(card.getAttribute("aria-label")).toContain("Ожидает подтверждения");
    expect(card.querySelector(".file-card__type")?.textContent).toBe("VID");
    expect(actions.getAttribute("role")).toBe("group");
    expect(Array.from(actions.querySelectorAll("button"), (button) => button.textContent)).toEqual([
      "Скачать",
      "Отменить",
    ]);

    view.render(active({ transfers: [{
      ...item("FAILED"),
      metadata: {
        ...item("FAILED").metadata,
        displayName: longName,
        mimeType: "video/mp4",
        direction: "ANDROID_TO_BROWSER",
      },
    }] }));
    expect(Array.from(
      document.querySelectorAll('[data-transfer-id="file-1"] .file-card__actions button'),
      (button) => button.textContent,
    )).toEqual(["Повторить"]);
  });
});

function createActions() {
  return {
    onSelect: vi.fn(),
    onConfirm: vi.fn(),
    onRemoveDraft: vi.fn(),
    onClearDraft: vi.fn(),
    onCancel: vi.fn(),
    onRetry: vi.fn(),
    onDownload: vi.fn(),
  };
}

function active(overrides: Partial<Extract<FileTransferUiState, { kind: "active" }>> = {}) {
  return {
    kind: "active" as const,
    connectionAvailable: true,
    selection: [],
    transfers: [],
    preparing: false,
    ...overrides,
  };
}

function item(
  status: "CONNECTING" | "TRANSFERRING" | "VERIFYING" | "COMPLETED" | "FAILED" | "CANCELLED",
) {
  return {
    id: "file-1",
    metadata: {
      transferId: "file-1", displayName: "report.bin", sizeBytes: 4,
      mimeType: "application/octet-stream", sha256: "a".repeat(64),
      direction: "ANDROID_TO_BROWSER" as const,
    },
    status,
    bytesTransferred: status === "VERIFYING" || status === "COMPLETED" ? 4 : 0,
    speedBytesPerSecond: 0,
  };
}
