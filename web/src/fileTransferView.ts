import type {
  FileTransferUiItem,
  FileTransferUiState,
} from "./fileTransferController";

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

export function createFileTransferView(
  documentRef: Document,
  actions: FileTransferActions,
): FileTransferView {
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
  const knownStatuses = new Map<string, string>();
  const transferCards = new Map<string, HTMLLIElement>();

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
  input.addEventListener("change", onInput);
  confirm.addEventListener("click", onConfirm);
  clearDraft.addEventListener("click", onClearDraft);
  dropZone.addEventListener("dragover", onDragOver);
  dropZone.addEventListener("drop", onDrop);
  dropZone.addEventListener("keydown", onDropKey);

  const render = (state: FileTransferUiState): void => {
    if (state.kind === "inactive") {
      section.hidden = true;
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
    reconcileTransferCards(documentRef, list, state.transfers, actions, transferCards);
    announceTerminalChanges(state.transfers, knownStatuses, announcer);
  };

  render({ kind: "inactive" });
  return {
    render,
    dispose: () => {
      input.removeEventListener("change", onInput);
      confirm.removeEventListener("click", onConfirm);
      clearDraft.removeEventListener("click", onClearDraft);
      dropZone.removeEventListener("dragover", onDragOver);
      dropZone.removeEventListener("drop", onDrop);
      dropZone.removeEventListener("keydown", onDropKey);
    },
  };
}

function createTransferCard(
  documentRef: Document,
  item: FileTransferUiItem,
  actions: FileTransferActions,
): HTMLLIElement {
  const card = documentRef.createElement("li");
  card.className = "file-card";
  card.dataset.transferId = item.id;

  const heading = documentRef.createElement("div");
  heading.className = "file-card__heading";
  const name = documentRef.createElement("strong");
  const status = documentRef.createElement("span");
  status.className = "file-card__status";
  heading.append(name, status);

  const details = documentRef.createElement("p");
  details.className = "file-card__details";
  card.append(heading, details);
  updateTransferCard(documentRef, card, item, actions);
  return card;
}

function updateTransferCard(
  documentRef: Document,
  card: HTMLLIElement,
  item: FileTransferUiItem,
  actions: FileTransferActions,
): void {
  card.dataset.status = item.status;
  const name = card.querySelector<HTMLElement>(".file-card__heading strong")!;
  const status = card.querySelector<HTMLElement>(".file-card__status")!;
  const details = card.querySelector<HTMLElement>(".file-card__details")!;
  name.textContent = item.metadata.displayName;
  status.textContent = statusLabel(item.status);
  details.textContent =
    directionLabel(item.metadata.direction) + " · " +
    formatBytes(item.metadata.sizeBytes) + " · " +
    item.metadata.mimeType;

  if (item.status === "TRANSFERRING" || item.status === "VERIFYING") {
    const progress = card.querySelector<HTMLProgressElement>("progress") ??
      documentRef.createElement("progress");
    progress.max = Math.max(1, item.metadata.sizeBytes);
    progress.value = item.bytesTransferred;
    progress.setAttribute(
      "aria-label",
      "Прогресс " + item.metadata.displayName + ": " + progressPercent(item) + "%",
    );
    const progressText = card.querySelector<HTMLElement>(".file-card__progress") ??
      documentRef.createElement("p");
    progressText.className = "file-card__progress";
    progressText.textContent =
      progressPercent(item) + "% · " +
      formatBytes(item.bytesTransferred) + " из " +
      formatBytes(item.metadata.sizeBytes) + " · " +
      formatBytes(item.speedBytesPerSecond) + "/с";
    if (!progress.isConnected) details.after(progress, progressText);
  } else {
    card.querySelector("progress")?.remove();
    card.querySelector(".file-card__progress")?.remove();
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
  syncActionButton(
    documentRef,
    actionsRow,
    "retry-file",
    canRetry,
    () => actionButton(
      documentRef,
      "Повторить",
      "retry-file",
      () => actions.onRetry(item.id),
      true,
    ),
  );
  if (actionsRow !== null && actionsRow.childElementCount === 0) {
    actionsRow.remove();
  }
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
      card = createTransferCard(documentRef, item, actions);
      cards.set(item.id, card);
    } else {
      updateTransferCard(documentRef, card, item, actions);
    }
    const currentAtIndex = list.children.item(index);
    if (currentAtIndex !== card) list.insertBefore(card, currentAtIndex);
  });
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
      messages.push(item.metadata.displayName + ": " + statusLabel(item.status));
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

function statusLabel(status: FileTransferUiItem["status"]): string {
  switch (status) {
    case "QUEUED": return "В очереди";
    case "CONNECTING": return "Ожидает подтверждения";
    case "TRANSFERRING": return "Передаётся";
    case "VERIFYING": return "Проверяется";
    case "COMPLETED": return "Завершено";
    case "CANCELLED": return "Отменено";
    case "FAILED": return "Ошибка";
  }
}

function directionLabel(direction: FileTransferUiItem["metadata"]["direction"]): string {
  return direction === "ANDROID_TO_BROWSER" ? "С телефона" : "На телефон";
}

function formatBytes(bytes: number): string {
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
