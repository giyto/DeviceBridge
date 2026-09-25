import { formatBytes } from "./byteFormat";
import { isTerminalStatus } from "./fileApiClient";
import type { FileTransferUiItem } from "./fileTransferModel";
import type { FileTransferActions } from "./fileTransferView";
import {
  directionLabel,
  transferItemPresentationState,
} from "./uiPresentationState";

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
  const canCancel = !isTerminalStatus(item.status);
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

export function reconcileTransferCards(
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

export function restoreTransferActionFocus(
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
export function actionButton(
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

export function announceTerminalChanges(
  items: readonly FileTransferUiItem[],
  knownStatuses: Map<string, string>,
  announcer: HTMLElement,
): void {
  const messages: string[] = [];
  for (const item of items) {
    const previous = knownStatuses.get(item.id);
    knownStatuses.set(item.id, item.status);
    if (isTerminalStatus(item.status) && previous !== item.status) {
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
