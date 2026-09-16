import type {
  FileTransferUiItem,
  FileTransferUiState,
} from "./fileTransferController";

export interface FileTransferActions {
  readonly onSelect: (files: readonly File[]) => void;
  readonly onConfirm: () => void;
  readonly onCancel: (transferId: string) => void;
  readonly onRetry: (transferId: string) => void;
  readonly onDownload: (transferId: string) => void;
  readonly onVerify: (transferId: string, file: File) => void;
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
  const error = required<HTMLElement>(documentRef, '[data-role="file-error"]');
  const list = required<HTMLOListElement>(documentRef, '[data-role="file-transfer-list"]');
  const empty = required<HTMLElement>(documentRef, '[data-role="file-transfer-empty"]');
  const count = required<HTMLElement>(documentRef, '[data-role="file-transfer-count"]');
  const verification = required<HTMLInputElement>(documentRef, "#file-verification-input");
  const announcer = required<HTMLElement>(documentRef, '[data-role="file-announcer"]');
  let verificationTransferId: string | undefined;
  const knownStatuses = new Map<string, string>();

  const select = (files: FileList | readonly File[] | null): void => {
    if (files === null) return;
    actions.onSelect(Array.from(files));
  };
  const onInput = (): void => select(input.files);
  const onConfirm = (): void => actions.onConfirm();
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
  const onVerification = (): void => {
    const transferId = verificationTransferId;
    const file = verification.files?.[0];
    verification.value = "";
    verificationTransferId = undefined;
    if (transferId !== undefined && file !== undefined) actions.onVerify(transferId, file);
  };

  input.addEventListener("change", onInput);
  confirm.addEventListener("click", onConfirm);
  dropZone.addEventListener("dragover", onDragOver);
  dropZone.addEventListener("drop", onDrop);
  dropZone.addEventListener("keydown", onDropKey);
  verification.addEventListener("change", onVerification);

  const render = (state: FileTransferUiState): void => {
    if (state.kind === "inactive") {
      section.hidden = true;
      selection.replaceChildren();
      list.replaceChildren();
      error.hidden = true;
      error.textContent = "";
      confirm.disabled = true;
      count.textContent = "0";
      empty.hidden = false;
      knownStatuses.clear();
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
        return row;
      }),
    );
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
    list.replaceChildren(
      ...state.transfers.map((item) =>
        createTransferCard(documentRef, item, actions, () => {
          verificationTransferId = item.id;
          verification.click();
        })
      ),
    );
    announceTerminalChanges(state.transfers, knownStatuses, announcer);
  };

  render({ kind: "inactive" });
  return {
    render,
    dispose: () => {
      input.removeEventListener("change", onInput);
      confirm.removeEventListener("click", onConfirm);
      dropZone.removeEventListener("dragover", onDragOver);
      dropZone.removeEventListener("drop", onDrop);
      dropZone.removeEventListener("keydown", onDropKey);
      verification.removeEventListener("change", onVerification);
    },
  };
}

function createTransferCard(
  documentRef: Document,
  item: FileTransferUiItem,
  actions: FileTransferActions,
  chooseVerification: () => void,
): HTMLLIElement {
  const card = documentRef.createElement("li");
  card.className = "file-card";
  card.dataset.transferId = item.id;
  card.dataset.status = item.status;

  const heading = documentRef.createElement("div");
  heading.className = "file-card__heading";
  const name = documentRef.createElement("strong");
  name.textContent = item.metadata.displayName;
  const status = documentRef.createElement("span");
  status.className = "file-card__status";
  status.textContent = statusLabel(item.status);
  heading.append(name, status);

  const details = documentRef.createElement("p");
  details.className = "file-card__details";
  details.textContent =
    directionLabel(item.metadata.direction) + " · " +
    formatBytes(item.metadata.sizeBytes) + " · " +
    item.metadata.mimeType;
  card.append(heading, details);

  if (item.status === "TRANSFERRING" || item.status === "VERIFYING") {
    const progress = documentRef.createElement("progress");
    progress.max = Math.max(1, item.metadata.sizeBytes);
    progress.value = item.bytesTransferred;
    progress.setAttribute(
      "aria-label",
      "Прогресс " + item.metadata.displayName + ": " + progressPercent(item) + "%",
    );
    const progressText = documentRef.createElement("p");
    progressText.className = "file-card__progress";
    progressText.textContent =
      progressPercent(item) + "% · " +
      formatBytes(item.bytesTransferred) + " из " +
      formatBytes(item.metadata.sizeBytes) + " · " +
      formatBytes(item.speedBytesPerSecond) + "/с";
    card.append(progress, progressText);
  }

  if (item.localError !== undefined) {
    const itemError = documentRef.createElement("p");
    itemError.className = "file-card__error";
    itemError.textContent = item.localError;
    card.append(itemError);
  }

  const actionsRow = documentRef.createElement("div");
  actionsRow.className = "file-card__actions";
  if (
    item.metadata.direction === "ANDROID_TO_BROWSER" &&
    item.status === "CONNECTING"
  ) {
    actionsRow.append(actionButton(
      documentRef,
      "Скачать",
      "download-file",
      () => actions.onDownload(item.id),
    ));
  }
  if (
    item.metadata.direction === "ANDROID_TO_BROWSER" &&
    item.status === "VERIFYING"
  ) {
    const verificationNote = documentRef.createElement("p");
    verificationNote.className = "file-card__verification-note";
    verificationNote.textContent =
      "На локальном HTTP браузер не может безопасно проверить нативную загрузку автоматически. " +
      "Выберите сохранённый файл для потоковой проверки SHA-256.";
    card.append(verificationNote);
    actionsRow.append(actionButton(
      documentRef,
      "Выбрать скачанный файл",
      "verify-file",
      chooseVerification,
    ));
  }
  if (!isTerminal(item.status)) {
    actionsRow.append(actionButton(
      documentRef,
      "Отменить",
      "cancel-file",
      () => actions.onCancel(item.id),
      true,
    ));
  }
  if (item.status === "FAILED" || item.status === "CANCELLED") {
    actionsRow.append(actionButton(
      documentRef,
      "Повторить",
      "retry-file",
      () => actions.onRetry(item.id),
      true,
    ));
  }
  if (actionsRow.childElementCount > 0) card.append(actionsRow);
  return card;
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
    case "VERIFYING": return "Нужна проверка";
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
