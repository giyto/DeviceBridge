import { formatBytes } from "./byteFormat";
import { required } from "./dom";
import {
  actionButton,
  announceTerminalChanges,
  reconcileTransferCards,
  restoreTransferActionFocus,
} from "./fileTransferCard";
import type { FileTransferUiState } from "./fileTransferModel";
import { isTextEntry, namePastedFiles } from "./pastedFiles";
import { filePresentationState } from "./uiPresentationState";

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
  const section = required<HTMLElement>(
    documentRef,
    '[data-role="file-transfer"]',
    "file transfer",
  );
  const input = required<HTMLInputElement>(documentRef, "#file-input", "file transfer");
  const dropZone = required<HTMLElement>(
    documentRef,
    '[data-role="file-drop-zone"]',
    "file transfer",
  );
  const selection = required<HTMLUListElement>(
    documentRef,
    '[data-role="file-selection"]',
    "file transfer",
  );
  const confirm = required<HTMLButtonElement>(
    documentRef,
    '[data-action="confirm-files"]',
    "file transfer",
  );
  const clearDraft = required<HTMLButtonElement>(
    documentRef,
    '[data-action="clear-file-draft"]',
    "file transfer",
  );
  const error = required<HTMLElement>(documentRef, '[data-role="file-error"]', "file transfer");
  const list = required<HTMLOListElement>(
    documentRef,
    '[data-role="file-transfer-list"]',
    "file transfer",
  );
  const empty = required<HTMLElement>(
    documentRef,
    '[data-role="file-transfer-empty"]',
    "file transfer",
  );
  const count = required<HTMLElement>(
    documentRef,
    '[data-role="file-transfer-count"]',
    "file transfer",
  );
  const announcer = required<HTMLElement>(
    documentRef,
    '[data-role="file-announcer"]',
    "file transfer",
  );
  const pasteHint = required<HTMLElement>(
    documentRef,
    '[data-role="file-paste-hint"]',
    "file transfer",
  );
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
