import type {
  FileTransferUiItem,
  FileTransferUiState,
} from "./fileTransferController";
import {
  filePresentationState,
  transferItemPresentationState,
} from "./uiPresentationState";

export interface FileTransferActions {
  readonly onSelect: (files: readonly File[]) => void;
  readonly onConfirm: () => void;
  readonly onRemoveDraft: (key: string) => void;
  readonly onClearDraft: () => void;
  readonly onCancel: (transferId: string) => void;
  readonly onRetry: (transferId: string) => void;
  readonly onDownload: (transferId: string) => void;
}

export interface FileTransferView {
  render(state: FileTransferUiState): void;
  dispose(): void;
}

export interface FileTransferViewOptions {
  /** Clock used to name pasted screenshots; injectable for tests. */
  readonly now?: () => Date;
}

const PASTE_HINT = "Скриншот можно вставить из буфера обмена: Ctrl+V.";
const PASTED_INTO_TEXT_MESSAGE = "Картинка добавлена в файлы.";

export function createFileTransferView(
  documentRef: Document,
  actions: FileTransferActions,
  options: FileTransferViewOptions = {},
): FileTransferView {
  const now = options.now ?? (() => new Date());
  const section = required<HTMLElement>(documentRef, '[data-role="file-transfer"]');
  const input = required<HTMLInputElement>(documentRef, "#file-input");
  const dropZone = required<HTMLElement>(documentRef, '[data-role="file-drop-zone"]');
  const selection = required<HTMLUListElement>(documentRef, '[data-role="file-selection"]');
  const confirm = required<HTMLButtonElement>(documentRef, '[data-action="confirm-files"]');
  const clearDraft = required<HTMLButtonElement>(documentRef, '[data-action="clear-file-draft"]');
  const error = required<HTMLElement>(documentRef, '[data-role="file-error"]');
  const list = required<HTMLOListElement>(documentRef, '[data-role="file-transfer-list"]');
  const empty = required<HTMLElement>(documentRef, '[data-role="file-transfer-empty"]');
  const count = required<HTMLElement>(documentRef, '[data-role="file-transfer-count"]');
  const announcer = required<HTMLElement>(documentRef, '[data-role="file-announcer"]');
  const pasteHint = required<HTMLElement>(documentRef, '[data-role="file-paste-hint"]');
  const knownStatuses = new Map<string, string>();
  const transferCards = new Map<string, HTMLLIElement>();
  let pendingActionFocus: Readonly<{ transferId: string; source: "cancel" | "retry" }> | undefined;
  const focusAwareActions: FileTransferActions = {
    ...actions,
    onCancel: (transferId) => {
      pendingActionFocus = { transferId, source: "cancel" };
      actions.onCancel(transferId);
    },
    onRetry: (transferId) => {
      pendingActionFocus = { transferId, source: "retry" };
      actions.onRetry(transferId);
    },
  };

  const select = (files: FileList | readonly File[] | null): void => {
    if (files === null) return;
    actions.onSelect(Array.from(files));
  };
  const onInput = (): void => {
    select(input.files);
    input.value = "";
  };
  const onConfirm = (): void => actions.onConfirm();
  const onClearDraft = (): void => actions.onClearDraft();
  const onDragOver = (event: DragEvent): void => {
    event.preventDefault();
    if (event.dataTransfer !== null) event.dataTransfer.dropEffect = "copy";
  };
  const onDrop = (event: DragEvent): void => {
    event.preventDefault();
    select(event.dataTransfer?.files ?? null);
  };
  const onDropKey = (event: KeyboardEvent): void => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      input.click();
    }
  };
  const onPaste = (event: ClipboardEvent): void => {
    // Only an authorized, visible file section accepts clipboard files.
    if (section.hidden) return;
    const clipboard = event.clipboardData;
    if (clipboard === null) return;
    const files = Array.from(clipboard.files ?? []);
    const inTextField = isTextEntry(event.target);
    if (inTextField && clipboard.getData("text/plain").trim() !== "") return;
    if (files.length === 0) return;
    event.preventDefault();
    select(namePastedFiles(files, now()));
    if (inTextField) {
      pasteHint.textContent = PASTED_INTO_TEXT_MESSAGE;
      announcer.textContent = PASTED_INTO_TEXT_MESSAGE;
    }
  };
  input.addEventListener("change", onInput);
  documentRef.addEventListener("paste", onPaste);
  confirm.addEventListener("click", onConfirm);
  clearDraft.addEventListener("click", onClearDraft);
  dropZone.addEventListener("dragover", onDragOver);
  dropZone.addEventListener("drop", onDrop);
  dropZone.addEventListener("keydown", onDropKey);

  const render = (state: FileTransferUiState): void => {
    section.dataset.viewState = filePresentationState(state);
    if (state.kind === "inactive") {
      section.hidden = true;
      pasteHint.textContent = PASTE_HINT;
      selection.replaceChildren();
      list.replaceChildren();
      error.hidden = true;
      error.textContent = "";
      confirm.disabled = true;
      clearDraft.hidden = true;
      clearDraft.disabled = true;
      count.textContent = "0";
      empty.hidden = false;
      knownStatuses.clear();
      transferCards.clear();
      pendingActionFocus = undefined;
      return;
    }

    section.hidden = false;
    selection.replaceChildren(
      ...state.selection.map((item) => {
        const row = documentRef.createElement("li");
        row.className = "file-selection__item";
        const name = documentRef.createElement("strong");
        name.textContent = item.displayName;
        const details = documentRef.createElement("span");
        details.textContent = formatBytes(item.sizeBytes) + " · " + item.mimeType;
        row.append(name, details);
        if (item.error !== undefined) {
          const itemError = documentRef.createElement("span");
          itemError.className = "file-selection__error";
          itemError.textContent = item.error;
          row.append(itemError);
        }
        const remove = actionButton(
          documentRef,
          "Удалить",
          "remove-draft-file",
          () => actions.onRemoveDraft(item.key),
          true,
        );
        remove.disabled = state.preparing;
        remove.setAttribute("aria-label", `Удалить ${item.displayName} из выбранных`);
        row.append(remove);
        return row;
      }),
    );
    clearDraft.hidden = state.selection.length === 0;
    clearDraft.disabled = state.preparing || state.selection.length === 0;
    confirm.disabled =
      !state.connectionAvailable ||
      state.preparing ||
      state.selection.length === 0 ||
      state.selection.every((item) => item.error !== undefined);
    confirm.textContent = state.preparing
      ? "Проверяем SHA-256…"
      : "Подтвердить отправку";
    error.hidden = state.error === undefined;
    error.textContent = state.error ?? "";
    count.textContent = String(state.transfers.length);
    empty.hidden = state.transfers.length > 0;
    reconcileTransferCards(documentRef, list, state.transfers, focusAwareActions, transferCards, state.connectionAvailable);
    restoreTransferActionFocus(transferCards, pendingActionFocus, (restored) => {
      if (restored) pendingActionFocus = undefined;
    });
    announceTerminalChanges(state.transfers, knownStatuses, announcer);
  };

  render({ kind: "inactive" });
  return {
    render,
    dispose: () => {
      input.removeEventListener("change", onInput);
      documentRef.removeEventListener("paste", onPaste);
      confirm.removeEventListener("click", onConfirm);
      clearDraft.removeEventListener("click", onClearDraft);
      dropZone.removeEventListener("dragover", onDragOver);
      dropZone.removeEventListener("drop", onDrop);
      dropZone.removeEventListener("keydown", onDropKey);
    },
  };
}

function isTextEntry(target: EventTarget | null): boolean {
  if (typeof HTMLTextAreaElement !== "undefined" && target instanceof HTMLTextAreaElement) return true;
  if (typeof HTMLInputElement !== "undefined" && target instanceof HTMLInputElement) {
    return target.type !== "file" && target.type !== "checkbox";
  }
  return target instanceof HTMLElement && target.isContentEditable;
}

const IMAGE_EXTENSIONS: Readonly<Record<string, string>> = {
  "image/png": "png",
  "image/jpeg": "jpg",
  "image/gif": "gif",
  "image/webp": "webp",
  "image/bmp": "bmp",
};

// Browsers name clipboard images generically ("image.png"); real file names are kept.
function namePastedFiles(files: readonly File[], pastedAt: Date): File[] {
  const base = "Скриншот " + formatPasteTimestamp(pastedAt);
  let screenshots = 0;
  return files.map((file) => {
    if (!file.type.startsWith("image/") || !/^(image(\.\w+)?|blob)?$/i.test(file.name)) {
      return file;
    }
    screenshots += 1;
    const extension = IMAGE_EXTENSIONS[file.type] ?? file.name.split(".").at(-1) ?? "png";
    const suffix = screenshots > 1 ? ` (${screenshots})` : "";
    return new File([file], `${base}${suffix}.${extension}`, {
      type: file.type,
      lastModified: pastedAt.getTime(),
    });
  });
}

function formatPasteTimestamp(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}-${pad(date.getMinutes())}-${pad(date.getSeconds())}`;
}

function createTransferCard(
  documentRef: Document,
  item: FileTransferUiItem,
  actions: FileTransferActions,
  connectionAvailable: boolean,
): HTMLLIElement {
  const card = documentRef.createElement("li");
  card.className = "file-card";
  card.dataset.transferId = item.id;

  const heading = documentRef.createElement("div");
  heading.className = "file-card__heading";
  const identity = documentRef.createElement("div");
  identity.className = "file-card__identity";
  const type = documentRef.createElement("span");
  type.className = "file-card__type";
  type.setAttribute("aria-hidden", "true");
  const name = documentRef.createElement("strong");
  identity.append(type, name);
  const status = documentRef.createElement("span");
  status.className = "file-card__status";
  heading.append(identity, status);

  const details = documentRef.createElement("p");
  details.className = "file-card__details";
  card.append(heading, details);
  updateTransferCard(documentRef, card, item, actions, connectionAvailable);
  return card;
}

function updateTransferCard(
  documentRef: Document,
  card: HTMLLIElement,
  item: FileTransferUiItem,
  actions: FileTransferActions,
  connectionAvailable: boolean,
): void {
  card.dataset.status = item.status;
  card.dataset.viewState = transferItemPresentationState(item.status);
  const type = card.querySelector<HTMLElement>(".file-card__type")!;
  const name = card.querySelector<HTMLElement>(".file-card__heading strong")!;
  const status = card.querySelector<HTMLElement>(".file-card__status")!;
  const details = card.querySelector<HTMLElement>(".file-card__details")!;
  type.textContent = fileTypeLabel(item.metadata.mimeType, item.metadata.displayName);
  name.textContent = item.metadata.displayName;
  status.textContent = statusLabel(item);
  card.setAttribute(
    "aria-label",
    `${directionLabel(item.metadata.direction)}. ${item.metadata.displayName}. ${statusLabel(item)}.`,
  );
  details.textContent =
    directionLabel(item.metadata.direction) + " · " +
    formatBytes(item.metadata.sizeBytes) + " · " +
    item.metadata.mimeType;

  if (item.status === "TRANSFERRING" || item.status === "VERIFYING") {
    const progress = card.querySelector<HTMLProgressElement>("progress") ??
      documentRef.createElement("progress");
    const progressText = card.querySelector<HTMLElement>(".file-card__progress") ??
      documentRef.createElement("p");
    progressText.className = "file-card__progress";
    if (item.status === "TRANSFERRING" && item.checkingSavedPart === true) {
      progress.removeAttribute("value");
      progress.setAttribute(
        "aria-label",
        "Проверка сохранённой части файла " + item.metadata.displayName,
      );
      progressText.textContent = "Проверка сохранённой части";
    } else if (item.status === "VERIFYING") {
      progress.removeAttribute("value");
      progress.setAttribute(
        "aria-label",
        "Проверяется целостность файла " + item.metadata.displayName,
      );
      progressText.textContent = "Проверяется целостность файла";
    } else {
      progress.max = Math.max(1, item.metadata.sizeBytes);
      progress.value = item.bytesTransferred;
      progress.setAttribute(
        "aria-label",
        "Прогресс " + item.metadata.displayName + ": " + progressPercent(item) + "%",
      );
      progressText.textContent =
        progressPercent(item) + "% · " +
        formatBytes(item.bytesTransferred) + " из " +
        formatBytes(item.metadata.sizeBytes) + " · " +
        formatBytes(item.speedBytesPerSecond) + "/с" +
        (item.resumedFromBytes === undefined
          ? ""
          : " · продолжение с " + formatBytes(item.resumedFromBytes));
    }
    if (!progress.isConnected) details.after(progress, progressText);
  } else {
    card.querySelector("progress")?.remove();
    card.querySelector(".file-card__progress")?.remove();
  }

  const resumeHint = resumeHintText(item);
  if (resumeHint !== undefined) {
    const hint = card.querySelector<HTMLElement>(".file-card__resume") ??
      documentRef.createElement("p");
    hint.className = "file-card__resume";
    hint.textContent = resumeHint;
    if (!hint.isConnected) details.after(hint);
  } else {
    card.querySelector(".file-card__resume")?.remove();
  }

  if (item.localError !== undefined) {
    const itemError = card.querySelector<HTMLElement>(".file-card__error") ??
      documentRef.createElement("p");
    itemError.className = "file-card__error";
    itemError.textContent = item.localError;
    if (!itemError.isConnected) {
      const actionsRow = card.querySelector(".file-card__actions");
      if (actionsRow === null) card.append(itemError);
      else card.insertBefore(itemError, actionsRow);
    }
  } else {
    card.querySelector(".file-card__error")?.remove();
  }

  const canDownload =
    item.metadata.direction === "ANDROID_TO_BROWSER" &&
    item.status === "CONNECTING";
  const canCancel = !isTerminal(item.status);
  const canRetry = item.status === "FAILED" || item.status === "CANCELLED";
  let actionsRow = card.querySelector<HTMLDivElement>(".file-card__actions");
  if (canDownload || canCancel || canRetry) {
    if (actionsRow === null) {
      actionsRow = documentRef.createElement("div");
      actionsRow.className = "file-card__actions";
      actionsRow.setAttribute("role", "group");
      actionsRow.setAttribute("aria-label", "Действия с файлом");
      card.append(actionsRow);
    }
  }
  syncActionButton(
    documentRef,
    actionsRow,
    "download-file",
    canDownload,
    () => actionButton(
      documentRef,
      "Скачать",
      "download-file",
      () => actions.onDownload(item.id),
    ),
  );
  syncActionButton(
    documentRef,
    actionsRow,
    "cancel-file",
    canCancel,
    () => actionButton(
      documentRef,
      "Отменить",
      "cancel-file",
      () => actions.onCancel(item.id),
      true,
    ),
  );
  const retryLabel = canContinueUpload(item) ? "Продолжить" : "Повторить";
  syncActionButton(
    documentRef,
    actionsRow,
    "retry-file",
    canRetry,
    () => actionButton(
      documentRef,
      retryLabel,
      "retry-file",
      () => actions.onRetry(item.id),
      true,
    ),
  );
  const retryButton = actionsRow?.querySelector<HTMLButtonElement>('[data-action="retry-file"]');
  if (retryButton !== null && retryButton !== undefined) retryButton.textContent = retryLabel;
  actionsRow?.querySelectorAll<HTMLButtonElement>("button").forEach((button) => {
    button.disabled = !connectionAvailable || item.checkingSourcePercent !== undefined;
  });
  if (actionsRow !== null && actionsRow.childElementCount === 0) {
    actionsRow.remove();
  }
}

function canContinueUpload(item: FileTransferUiItem): boolean {
  return item.status === "FAILED" &&
    item.metadata.direction === "BROWSER_TO_ANDROID" &&
    item.resumableBytes !== undefined;
}

function resumeHintText(item: FileTransferUiItem): string | undefined {
  if (item.checkingSourcePercent !== undefined) {
    return "Проверяем исходный файл перед повтором… " + item.checkingSourcePercent + "%";
  }
  if (item.status !== "FAILED" || item.resumableBytes === undefined) return undefined;
  if (item.metadata.direction === "BROWSER_TO_ANDROID") {
    return "Сохранено " + formatBytes(item.resumableBytes) + " из " +
      formatBytes(item.metadata.sizeBytes) + ". Передачу можно продолжить с этого места.";
  }
  return "Загрузку можно возобновить в менеджере загрузок браузера в течение 15 минут " +
    "или повторить с начала.";
}

function syncActionButton(
  _documentRef: Document,
  actionsRow: HTMLDivElement | null,
  action: string,
  visible: boolean,
  create: () => HTMLButtonElement,
): void {
  const existing = actionsRow?.querySelector<HTMLButtonElement>(
    `[data-action="${action}"]`,
  );
  if (!visible) {
    existing?.remove();
  } else if (existing === null || existing === undefined) {
    actionsRow?.append(create());
  }
}

function reconcileTransferCards(
  documentRef: Document,
  list: HTMLOListElement,
  items: readonly FileTransferUiItem[],
  actions: FileTransferActions,
  cards: Map<string, HTMLLIElement>,
  connectionAvailable: boolean,
): void {
  const activeIds = new Set(items.map((item) => item.id));
  for (const [id, card] of cards) {
    if (!activeIds.has(id)) {
      card.remove();
      cards.delete(id);
    }
  }
  items.forEach((item, index) => {
    let card = cards.get(item.id);
    if (card === undefined) {
      card = createTransferCard(documentRef, item, actions, connectionAvailable);
      cards.set(item.id, card);
    } else {
      updateTransferCard(documentRef, card, item, actions, connectionAvailable);
    }
    const currentAtIndex = list.children.item(index);
    if (currentAtIndex !== card) list.insertBefore(card, currentAtIndex);
  });
}

function restoreTransferActionFocus(
  cards: ReadonlyMap<string, HTMLLIElement>,
  intent: Readonly<{ transferId: string; source: "cancel" | "retry" }> | undefined,
  complete: (restored: boolean) => void,
): void {
  if (intent === undefined) return;
  const card = cards.get(intent.transferId);
  const preferredAction = intent.source === "cancel" ? "retry-file" : "cancel-file";
  const target = card?.querySelector<HTMLButtonElement>(
    `[data-action="${preferredAction}"]:not(:disabled)`,
  );
  if (target === null || target === undefined) return;
  target.focus();
  complete(true);
}
function actionButton(
  documentRef: Document,
  label: string,
  action: string,
  listener: () => void,
  secondary = false,
): HTMLButtonElement {
  const button = documentRef.createElement("button");
  button.type = "button";
  button.dataset.action = action;
  button.className = secondary ? "secondary-button" : "primary-button";
  button.textContent = label;
  button.addEventListener("click", listener);
  return button;
}

function announceTerminalChanges(
  items: readonly FileTransferUiItem[],
  knownStatuses: Map<string, string>,
  announcer: HTMLElement,
): void {
  const messages: string[] = [];
  for (const item of items) {
    const previous = knownStatuses.get(item.id);
    knownStatuses.set(item.id, item.status);
    if (isTerminal(item.status) && previous !== item.status) {
      messages.push(item.metadata.displayName + ": " + statusLabel(item));
    }
  }
  if (messages.length > 0) announcer.textContent = messages.join(". ");
}

function progressPercent(item: FileTransferUiItem): number {
  if (item.metadata.sizeBytes === 0) return item.status === "COMPLETED" ? 100 : 0;
  return Math.max(
    0,
    Math.min(100, Math.round(item.bytesTransferred * 100 / item.metadata.sizeBytes)),
  );
}

function isTerminal(status: FileTransferUiItem["status"]): boolean {
  return status === "COMPLETED" || status === "CANCELLED" || status === "FAILED";
}

function statusLabel(item: FileTransferUiItem): string {
  switch (item.status) {
    case "QUEUED": return "В очереди";
    // A phone offer waits for this browser's download click, not for an approval.
    case "CONNECTING":
      return item.metadata.direction === "ANDROID_TO_BROWSER" ? "Ожидает скачивания" : "Ожидает подтверждения";
    case "TRANSFERRING": return "Передаётся";
    case "VERIFYING": return "Проверяется";
    case "COMPLETED": return "Завершено";
    case "CANCELLED": return item.cancelledOnPhone === true ? "Отменено на телефоне" : "Отменено";
    case "FAILED": return "Ошибка";
  }
}

function fileTypeLabel(mimeType: string, displayName: string): string {
  const normalizedMime = mimeType.toLowerCase();
  const extension = displayName.toLowerCase().split(".").at(-1) ?? "";
  if (normalizedMime.startsWith("image/")) return "IMG";
  if (normalizedMime.startsWith("video/")) return "VID";
  if (normalizedMime.startsWith("audio/")) return "AUD";
  if (normalizedMime.startsWith("text/")) return "TXT";
  if (normalizedMime === "application/pdf" || extension === "pdf") return "PDF";
  if (
    normalizedMime.includes("zip") ||
    normalizedMime.includes("compressed") ||
    ["zip", "7z", "rar", "tar", "gz"].includes(extension)
  ) return "ZIP";
  return "FILE";
}
function directionLabel(direction: FileTransferUiItem["metadata"]["direction"]): string {
  return direction === "ANDROID_TO_BROWSER" ? "С телефона" : "На телефон";
}

export function formatBytes(bytes: number): string {
  if (bytes >= 1024 ** 3) return (bytes / 1024 ** 3).toFixed(1) + " ГБ";
  if (bytes >= 1024 ** 2) return (bytes / 1024 ** 2).toFixed(1) + " МБ";
  if (bytes >= 1024) return (bytes / 1024).toFixed(1) + " КБ";
  return bytes + " Б";
}

function required<T extends Element>(documentRef: Document, selector: string): T {
  const element = documentRef.querySelector<T>(selector);
  if (element === null) throw new Error("Missing DeviceBridge file element: " + selector);
  return element;
}
