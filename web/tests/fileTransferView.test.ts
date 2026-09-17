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
    input.dispatchEvent(new Event("change", { bubbles: true }));
    expect(actions.onSelect).toHaveBeenCalledWith(files);
    expect(actions.onConfirm).not.toHaveBeenCalled();

    const zone = document.querySelector<HTMLElement>('[data-role="file-drop-zone"]')!;
    const drop = new Event("drop", { bubbles: true, cancelable: true }) as DragEvent;
    Object.defineProperty(drop, "dataTransfer", { value: { files } });
    zone.dispatchEvent(drop);
    expect(actions.onSelect).toHaveBeenLastCalledWith(files);
    expect(zone.tabIndex).toBe(0);
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

  it("exposes download and retry without asking to reselect a downloaded file", () => {
    const actions = createActions();
    const view = createFileTransferView(document, actions);
    view.render(active({ transfers: [item("CONNECTING")] }));
    document.querySelector<HTMLButtonElement>('[data-action="download-file"]')?.click();
    expect(actions.onDownload).toHaveBeenCalledWith("file-1");

    view.render(active({ transfers: [item("VERIFYING")] }));
    expect(document.querySelector('[data-action="verify-file"]')).toBeNull();
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
});

function createActions() {
  return {
    onSelect: vi.fn(),
    onConfirm: vi.fn(),
    onCancel: vi.fn(),
    onRetry: vi.fn(),
    onDownload: vi.fn(),
  };
}

function active(overrides: Partial<Extract<FileTransferUiState, { kind: "active" }>> = {}) {
  return {
    kind: "active" as const,
    selection: [],
    transfers: [],
    preparing: false,
    ...overrides,
  };
}

function item(status: "CONNECTING" | "VERIFYING" | "COMPLETED" | "FAILED") {
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
