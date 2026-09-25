import { fileLimitMessage } from "./byteFormat";
import {
  FileApiError,
  fileErrorMessage,
  isTerminalStatus,
  type FileErrorEvent,
  type FileMetadata,
  type FileSnapshotItem,
  type FileTransferStatus,
} from "./fileApiClient";

export const MAX_TRANSFER_ITEMS = 100;

export interface FileSelectionPreview {
  readonly key: string;
  readonly displayName: string;
  readonly sizeBytes: number;
  readonly mimeType: string;
  readonly error?: string;
}

export interface FileTransferUiItem {
  readonly id: string;
  readonly metadata: FileMetadata;
  readonly status: FileTransferStatus;
  readonly bytesTransferred: number;
  readonly speedBytesPerSecond: number;
  readonly localError?: string;
  /** A failed item can continue from these bytes (server-reported). */
  readonly resumableBytes?: number;
  /** The server is reading the part it kept before the upload continues. */
  readonly checkingSavedPart?: boolean;
  /** The running upload continues after this many bytes kept on the phone. */
  readonly resumedFromBytes?: number;
  /** Percent of the source file re-read before a retry (the source must be unchanged). */
  readonly checkingSourcePercent?: number;
  /** Cancelled on the phone rather than in this browser. */
  readonly cancelledOnPhone?: boolean;
}

export type FileTransferUiState =
  | Readonly<{ kind: "inactive" }>
  | Readonly<{
      kind: "active";
      connectionAvailable: boolean;
      selection: readonly FileSelectionPreview[];
      transfers: readonly FileTransferUiItem[];
      preparing: boolean;
      error?: string;
    }>;

export function toUiItem(item: FileSnapshotItem): FileTransferUiItem {
  return {
    id: item.metadata.transferId,
    metadata: item.metadata,
    status: item.status,
    bytesTransferred: item.bytesTransferred,
    speedBytesPerSecond: item.speedBytesPerSecond,
    ...(item.resumableBytes === undefined ? {} : { resumableBytes: item.resumableBytes }),
  };
}

export function upsertBounded(
  items: readonly FileTransferUiItem[],
  item: FileTransferUiItem,
): readonly FileTransferUiItem[] {
  const existing = items.find((candidate) => candidate.id === item.id);
  const safeItem = existing === undefined
    ? item
    : {
        ...item,
        bytesTransferred: item.status === existing.status
          ? Math.max(existing.bytesTransferred, item.bytesTransferred)
          : item.bytesTransferred,
        // Local upload stages outlive server updates until the item settles.
        checkingSavedPart: isSettled(item.status)
          ? undefined
          : item.checkingSavedPart ?? existing.checkingSavedPart,
        resumedFromBytes: isSettled(item.status)
          ? undefined
          : item.resumedFromBytes ?? existing.resumedFromBytes,
      };
  return keepLast(existing === undefined
    ? [...items, safeItem]
    : items.map((candidate) => candidate.id === item.id ? safeItem : candidate));
}

const ACTIVE_STAGE_ORDER: ReadonlyArray<FileTransferStatus> = [
  "QUEUED",
  "CONNECTING",
  "TRANSFERRING",
  "VERIFYING",
];

/** Within one attempt an item only moves forward; an older answer must not move it back. */
export function isStaleStage(current: FileTransferStatus, incoming: FileTransferStatus): boolean {
  const currentIndex = ACTIVE_STAGE_ORDER.indexOf(current);
  const incomingIndex = ACTIVE_STAGE_ORDER.indexOf(incoming);
  return currentIndex >= 0 && incomingIndex >= 0 && incomingIndex < currentIndex;
}

function isSettled(status: FileTransferStatus): boolean {
  return isTerminalStatus(status) || status === "QUEUED";
}

export function appendBounded(
  items: readonly FileTransferUiItem[],
  item: FileTransferUiItem,
): readonly FileTransferUiItem[] {
  return keepLast([...items, item]);
}

/** Only the newest transfers stay on the page. */
function keepLast(items: readonly FileTransferUiItem[]): readonly FileTransferUiItem[] {
  return items.length <= MAX_TRANSFER_ITEMS ? items : items.slice(items.length - MAX_TRANSFER_ITEMS);
}

export function safeErrorMessage(error: unknown): string {
  if (error instanceof FileApiError) return error.message;
  return error instanceof Error && error.message.startsWith("Сеть")
    ? error.message
    : "Не удалось выполнить файловую операцию.";
}

/** What a phone-reported file error says on the page; most codes read as the API error does. */
export function fileCodeMessage(
  code: FileErrorEvent["code"],
  effectiveFileLimitBytes: number,
): string {
  switch (code) {
    case "FILE_TOO_LARGE": return fileLimitMessage(effectiveFileLimitBytes);
    case "NOT_APPROVED": return "Подтвердите передачу на телефоне.";
    case "SESSION_UNAVAILABLE": return "Сессия браузера завершена.";
    case "INVALID_PAYLOAD": return "Некорректная файловая операция.";
    default: return fileErrorMessage(code);
  }
}

export function matchesSourceMetadata(file: File, metadata: FileMetadata): boolean {
  return file.size === metadata.sizeBytes &&
    defaultDisplayName(file, metadata.transferId) === metadata.displayName &&
    mimeOf(file) === metadata.mimeType;
}

/** The name offered to the phone; a nameless file is called after its transfer. */
export function defaultDisplayName(file: File, transferId: string): string {
  return file.name || "file-" + transferId.slice(0, 8);
}

export function mimeOf(file: File): string {
  return file.type || "application/octet-stream";
}

export function selectionError(file: File, effectiveFileLimitBytes: number): string | undefined {
  if (!Number.isSafeInteger(file.size) || file.size < 0) {
    return "Не удалось определить размер файла.";
  }
  if (file.size > effectiveFileLimitBytes) {
    return fileLimitMessage(effectiveFileLimitBytes);
  }
  if (
    file.name.length > 255 ||
    [...file.name].some((character) => {
      const code = character.codePointAt(0) ?? 0;
      return code <= 0x1f || code === 0x7f;
    })
  ) {
    return "Имя файла не поддерживается.";
  }
  if (file.type.length > 127 || [...file.type].some((character) => character.charCodeAt(0) <= 0x1f)) {
    return "Тип файла не поддерживается.";
  }
  return undefined;
}
