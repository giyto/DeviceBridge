// @vitest-environment jsdom

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createFileTransferView } from "../src/fileTransferView";

const PASTED_AT = new Date(2026, 8, 23, 14, 5, 7);

beforeEach(() => {
  document.open();
  document.write(readFileSync(resolve(process.cwd(), "index.html"), "utf8"));
  document.close();
});

describe("clipboard image paste into the file draft", () => {
  it("adds a pasted screenshot outside the text field with a readable name and no upload", () => {
    const actions = createActions();
    createFileTransferView(document, actions, { now: () => PASTED_AT }).render(active());

    const event = paste(document.body, { files: [image("image.png", "image/png")] });

    expect(event.defaultPrevented).toBe(true);
    const [files] = actions.onSelect.mock.calls[0]!;
    expect(files).toHaveLength(1);
    expect(files[0].name).toBe("Скриншот 2026-09-23 14-05-07.png");
    expect(files[0].type).toBe("image/png");
    expect(actions.onConfirm).not.toHaveBeenCalled();
  });

  it("uses an extension that matches the image type", () => {
    const actions = createActions();
    createFileTransferView(document, actions, { now: () => PASTED_AT }).render(active());

    paste(document.body, {
      files: [image("image.jpeg", "image/jpeg"), image("image.png", "image/png")],
    });

    const names = actions.onSelect.mock.calls[0]![0].map((file: File) => file.name);
    expect(names).toEqual([
      "Скриншот 2026-09-23 14-05-07.jpg",
      "Скриншот 2026-09-23 14-05-07 (2).png",
    ]);
  });

  it("keeps real names of files that the browser provides on paste", () => {
    const actions = createActions();
    createFileTransferView(document, actions, { now: () => PASTED_AT }).render(active());

    paste(document.body, { files: [new File(["pdf"], "отчёт.pdf", { type: "application/pdf" })] });

    expect(actions.onSelect.mock.calls[0]![0][0].name).toBe("отчёт.pdf");
  });

  it("leaves ordinary text paste in the message field untouched", () => {
    const actions = createActions();
    createFileTransferView(document, actions, { now: () => PASTED_AT }).render(active());
    const draft = document.querySelector<HTMLTextAreaElement>("#text-draft")!;

    const event = paste(draft, {
      text: "hello",
      files: [image("image.png", "image/png")],
    });

    expect(event.defaultPrevented).toBe(false);
    expect(actions.onSelect).not.toHaveBeenCalled();
  });

  it("moves an image pasted into the message field to files and says so", () => {
    const actions = createActions();
    createFileTransferView(document, actions, { now: () => PASTED_AT }).render(active());
    const draft = document.querySelector<HTMLTextAreaElement>("#text-draft")!;
    draft.value = "keep me";

    const event = paste(draft, { files: [image("image.png", "image/png")] });

    expect(event.defaultPrevented).toBe(true);
    expect(actions.onSelect).toHaveBeenCalledOnce();
    expect(draft.value).toBe("keep me");
    expect(document.querySelector('[data-role="file-announcer"]')?.textContent)
      .toContain("Картинка добавлена в файлы");
    expect(document.querySelector('[data-role="file-paste-hint"]')?.textContent)
      .toContain("Картинка добавлена в файлы");
  });

  it("ignores a paste without images or files", () => {
    const actions = createActions();
    createFileTransferView(document, actions, { now: () => PASTED_AT }).render(active());

    const event = paste(document.body, { text: "just text" });

    expect(event.defaultPrevented).toBe(false);
    expect(actions.onSelect).not.toHaveBeenCalled();
  });

  it("ignores pastes before authorization and after dispose", () => {
    const actions = createActions();
    const view = createFileTransferView(document, actions, { now: () => PASTED_AT });

    paste(document.body, { files: [image("image.png", "image/png")] });
    expect(actions.onSelect).not.toHaveBeenCalled();

    view.render(active());
    view.dispose();
    paste(document.body, { files: [image("image.png", "image/png")] });
    expect(actions.onSelect).not.toHaveBeenCalled();
  });

  it("shows a Ctrl+V hint next to the file selection zone", () => {
    createFileTransferView(document, createActions()).render(active());

    expect(document.querySelector('[data-role="file-paste-hint"]')?.textContent)
      .toContain("Ctrl+V");
  });
});

function paste(
  target: EventTarget,
  data: { readonly text?: string; readonly files?: readonly File[] },
): Event {
  const event = new Event("paste", { bubbles: true, cancelable: true });
  Object.defineProperty(event, "clipboardData", {
    value: {
      files: data.files ?? [],
      getData: (type: string) => (type === "text/plain" ? data.text ?? "" : ""),
    },
  });
  target.dispatchEvent(event);
  return event;
}

function image(name: string, type: string): File {
  return new File([new Uint8Array([137, 80, 78, 71])], name, { type });
}

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

function active() {
  return {
    kind: "active" as const,
    connectionAvailable: true,
    selection: [],
    transfers: [],
    preparing: false,
  };
}
