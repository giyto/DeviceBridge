import {
  FileApiError,
  HARD_MAX_FILE_BYTES,
  type FileApiClient,
  type FileErrorEvent,
  type FileMetadata,
  type FileOfferEvent,
  type FileProgressEvent,
  type FileSnapshotEvent,
  type FileSnapshotItem,
  type FileTransferStatus,
} from "./fileApiClient";
import type { NativeFileDownloader } from "./nativeFileDownloader";
import type { XhrFileUploader } from "./xhrFileUploader";

const MAX_BATCH_ITEMS = 32;
const MAX_TRANSFER_ITEMS = 100;

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
}

export type FileTransferUiState =
  | Readonly<{ kind: "inactive" }>
  | Readonly<{
      kind: "active";
      selection: readonly FileSelectionPreview[];
      transfers: readonly FileTransferUiItem[];
      preparing: boolean;
      error?: string;
    }>;

export interface FileApi {
  offer: FileApiClient["offer"];
  requestDownloadGrant: FileApiClient["requestDownloadGrant"];
  verify: FileApiClient["verify"];
  cancel: FileApiClient["cancel"];
}

export interface FileUploader {
  upload: XhrFileUploader["upload"];
}

export interface FileDownloader {
  start: NativeFileDownloader["start"];
}

type FileHasher = (
  file: File,
  onProgress?: (bytesRead: number, totalBytes: number) => void,
  signal?: AbortSignal,
) => Promise<string>;

export class FileTransferController {
  private generation = 0;
  private token?: string;
  private state: FileTransferUiState = { kind: "inactive" };
  private selectedFiles: File[] = [];
  private readonly sourceFiles = new Map<string, File>();
  private readonly operations = new Map<string, AbortController>();

  constructor(
    private readonly api: FileApi,
    private readonly uploader: FileUploader,
    private readonly downloader: FileDownloader,
    private readonly hashFile: FileHasher,
    private readonly onStateChange: (state: FileTransferUiState) => void,
    private readonly createId: () => string,
    private readonly now: () => number = () => Date.now(),
    private readonly onUnauthorized: () => void = () => undefined,
  ) {}

  activate(token: string): void {
    if (this.state.kind === "active" && this.token === token) return;
    this.resetOperations();
    this.generation += 1;
    this.token = token;
    this.selectedFiles = [];
    this.sourceFiles.clear();
    this.emit({ kind: "active", selection: [], transfers: [], preparing: false });
  }

  deactivate(): void {
    this.resetOperations();
    this.generation += 1;
    this.token = undefined;
    this.selectedFiles = [];
    this.sourceFiles.clear();
    this.emit({ kind: "inactive" });
  }

  dispose(): void {
    this.deactivate();
  }

  currentState(): FileTransferUiState {
    return this.state;
  }

  selectFiles(files: readonly File[]): void {
    if (this.state.kind !== "active") return;
    const limited = [...files].slice(0, MAX_BATCH_ITEMS);
    this.selectedFiles = limited.filter((file) => selectionError(file) === undefined);
    const selection = limited.map((file, index): FileSelectionPreview => {
      const error = selectionError(file);
      return {
        key: "selection-" + index + "-" + file.name,
        displayName: file.name || "file-" + (index + 1),
        sizeBytes: file.size,
        mimeType: file.type || "application/octet-stream",
        ...(error === undefined ? {} : { error }),
      };
    });
    const overflow = files.length > MAX_BATCH_ITEMS
      ? "За один раз можно выбрать не более 32 файлов."
      : undefined;
    this.emit({ ...this.state, selection, error: overflow });
  }

  async confirmSelection(): Promise<void> {
    if (
      this.state.kind !== "active" ||
      this.token === undefined ||
      this.state.preparing ||
      this.selectedFiles.length === 0
    ) return;
    const generation = this.generation;
    const token = this.token;
    const controller = new AbortController();
    this.operations.set("selection", controller);
    this.emit({ ...this.state, preparing: true, error: undefined });
    try {
      const items: FileMetadata[] = [];
      for (const file of this.selectedFiles) {
        const transferId = this.createId();
        const sha256 = await this.hashFile(file, undefined, controller.signal);
        if (!this.isCurrent(generation, token)) return;
        const metadata: FileMetadata = {
          transferId,
          displayName: file.name || "file-" + transferId.slice(0, 8),
          sizeBytes: file.size,
          mimeType: file.type || "application/octet-stream",
          sha256,
          direction: "BROWSER_TO_ANDROID",
        };
        items.push(metadata);
        this.sourceFiles.set(transferId, file);
      }
      const messageId = this.createId();
      const snapshot = await this.api.offer(token, {
        messageId,
        timestamp: this.now(),
        batchId: this.createId(),
        items,
      }, controller.signal);
      if (!this.isCurrent(generation, token)) return;
      this.selectedFiles = [];
      this.applySnapshot(snapshot);
      if (this.state.kind === "active") {
        this.emit({
          ...this.state,
          selection: [],
          preparing: false,
          error: undefined,
        });
      }
    } catch (error: unknown) {
      if (!this.isCurrent(generation, token) || isAbortError(error)) return;
      this.handleOperationError(error);
    } finally {
      if (this.operations.get("selection") === controller) {
        this.operations.delete("selection");
      }
      if (this.state.kind === "active" && this.state.preparing) {
        this.emit({ ...this.state, preparing: false });
      }
    }
  }

  receiveOffer(event: FileOfferEvent): void {
    if (this.state.kind !== "active") return;
    let transfers = this.state.transfers;
    for (const metadata of event.items) {
      if (metadata.direction !== "ANDROID_TO_BROWSER") continue;
      if (transfers.some((item) => item.id === metadata.transferId)) continue;
      transfers = appendBounded(transfers, {
        id: metadata.transferId,
        metadata,
        status: "CONNECTING",
        bytesTransferred: 0,
        speedBytesPerSecond: 0,
      });
    }
    this.emit({ ...this.state, transfers });
  }

  receiveProgress(event: FileProgressEvent): void {
    if (this.state.kind !== "active") return;
    const existing = this.state.transfers.find((item) => item.id === event.transferId);
    if (existing === undefined) return;
    const bytesTransferred = Math.max(existing.bytesTransferred, event.bytesTransferred);
    this.upsert({
      ...existing,
      status: event.status,
      bytesTransferred,
      speedBytesPerSecond: event.speedBytesPerSecond,
      localError: undefined,
    });
    if (
      event.status === "TRANSFERRING" &&
      existing.metadata.direction === "BROWSER_TO_ANDROID"
    ) {
      this.startUpload(existing.id);
    }
  }

  applySnapshot(event: FileSnapshotEvent): void {
    if (this.state.kind !== "active") return;
    let transfers = this.state.transfers;
    for (const item of event.items) {
      transfers = upsertBounded(transfers, toUiItem(item));
    }
    this.emit({ ...this.state, transfers });
  }

  receiveError(event: FileErrorEvent): void {
    if (this.state.kind !== "active") return;
    if (event.code === "UNAUTHORIZED" || event.code === "SESSION_UNAVAILABLE") {
      this.deactivate();
      this.onUnauthorized();
      return;
    }
    if (event.transferId !== undefined) {
      const existing = this.state.transfers.find((item) => item.id === event.transferId);
      if (existing !== undefined) {
        this.upsert({ ...existing, status: "FAILED", localError: fileCodeMessage(event.code) });
      }
    }
    this.emitError(fileCodeMessage(event.code));
  }

  async download(transferId: string): Promise<void> {
    const context = this.operationContext(transferId);
    if (context === undefined) return;
    const item = this.transfer(transferId);
    if (item === undefined || item.metadata.direction !== "ANDROID_TO_BROWSER") return;
    try {
      const grant = await this.api.requestDownloadGrant(
        context.token,
        transferId,
        this.createId(),
        this.now(),
        context.controller.signal,
      );
      if (!this.isCurrent(context.generation, context.token)) return;
      this.downloader.start(grant.downloadPath, item.metadata.displayName);
    } catch (error: unknown) {
      if (!isAbortError(error)) this.handleOperationError(error, transferId);
    } finally {
      this.finishOperation(transferId, context.controller);
    }
  }

  async verifyDownloaded(transferId: string, file: File): Promise<void> {
    const context = this.operationContext(transferId);
    if (context === undefined) return;
    const item = this.transfer(transferId);
    if (item === undefined || item.metadata.direction !== "ANDROID_TO_BROWSER") {
      this.finishOperation(transferId, context.controller);
      return;
    }
    try {
      if (file.size > HARD_MAX_FILE_BYTES) {
        this.failLocal(transferId, "Выбранный файл превышает лимит проверки 1 ГиБ.");
        return;
      }
      const sha256 = await this.hashFile(file, undefined, context.controller.signal);
      if (!this.isCurrent(context.generation, context.token)) return;
      const sizeMatches = file.size === item.metadata.sizeBytes;
      const checksumMatches = sha256.toLowerCase() === item.metadata.sha256.toLowerCase();
      const snapshot = await this.api.verify(
        context.token,
        transferId,
        this.createId(),
        this.now(),
        file.size,
        sha256,
        context.controller.signal,
      );
      if (!this.isCurrent(context.generation, context.token)) return;
      if (sizeMatches && checksumMatches) {
        this.applySnapshot(snapshot);
      } else {
        this.failLocal(
          transferId,
          sizeMatches
            ? "Контрольная сумма выбранного файла не совпала."
            : "Размер выбранного файла не совпадает.",
        );
      }
    } catch (error: unknown) {
      if (!isAbortError(error)) this.handleOperationError(error, transferId);
    } finally {
      this.finishOperation(transferId, context.controller);
    }
  }

  async cancel(transferId: string): Promise<void> {
    if (this.state.kind !== "active" || this.token === undefined) return;
    this.operations.get(transferId)?.abort();
    const controller = new AbortController();
    this.operations.set(transferId, controller);
    const generation = this.generation;
    const token = this.token;
    try {
      const snapshot = await this.api.cancel(token, transferId, controller.signal);
      if (this.isCurrent(generation, token)) this.applySnapshot(snapshot);
    } catch (error: unknown) {
      if (!isAbortError(error)) this.handleOperationError(error, transferId);
    } finally {
      this.finishOperation(transferId, controller);
    }
  }

  async retry(transferId: string): Promise<void> {
    const item = this.transfer(transferId);
    const source = this.sourceFiles.get(transferId);
    if (
      this.state.kind !== "active" ||
      item === undefined ||
      source === undefined ||
      item.metadata.direction !== "BROWSER_TO_ANDROID"
    ) return;
    this.selectedFiles = [source];
    this.emit({
      ...this.state,
      selection: [{
        key: "retry-" + transferId,
        displayName: source.name,
        sizeBytes: source.size,
        mimeType: source.type || "application/octet-stream",
      }],
      error: undefined,
    });
    await this.confirmSelection();
  }

  private startUpload(transferId: string): void {
    if (
      this.state.kind !== "active" ||
      this.token === undefined ||
      this.operations.has(transferId)
    ) return;
    const file = this.sourceFiles.get(transferId);
    if (file === undefined) return;
    const controller = new AbortController();
    const generation = this.generation;
    const token = this.token;
    const startedAt = this.now();
    this.operations.set(transferId, controller);
    void this.uploader.upload(
      token,
      transferId,
      file,
      (bytes) => {
        const item = this.transfer(transferId);
        if (item === undefined || !this.isCurrent(generation, token)) return;
        const elapsedSeconds = Math.max(1, (this.now() - startedAt) / 1_000);
        this.upsert({
          ...item,
          status: "TRANSFERRING",
          bytesTransferred: Math.max(item.bytesTransferred, bytes),
          speedBytesPerSecond: Math.round(bytes / elapsedSeconds),
        });
      },
      controller.signal,
    ).then((snapshot) => {
      if (this.isCurrent(generation, token)) this.applySnapshot(snapshot);
    }).catch((error: unknown) => {
      if (!isAbortError(error) && this.isCurrent(generation, token)) {
        this.handleOperationError(error, transferId);
      }
    }).finally(() => this.finishOperation(transferId, controller));
  }

  private operationContext(transferId: string) {
    if (this.state.kind !== "active" || this.token === undefined) return undefined;
    this.operations.get(transferId)?.abort();
    const controller = new AbortController();
    this.operations.set(transferId, controller);
    return { controller, generation: this.generation, token: this.token };
  }

  private finishOperation(transferId: string, controller: AbortController): void {
    if (this.operations.get(transferId) === controller) this.operations.delete(transferId);
  }

  private transfer(transferId: string): FileTransferUiItem | undefined {
    return this.state.kind === "active"
      ? this.state.transfers.find((item) => item.id === transferId)
      : undefined;
  }

  private upsert(item: FileTransferUiItem): void {
    if (this.state.kind !== "active") return;
    this.emit({ ...this.state, transfers: upsertBounded(this.state.transfers, item) });
  }

  private failLocal(transferId: string, message: string): void {
    const item = this.transfer(transferId);
    if (item !== undefined) this.upsert({ ...item, status: "FAILED", localError: message });
    this.emitError(message);
  }

  private emitError(message: string): void {
    if (this.state.kind === "active") this.emit({ ...this.state, error: message, preparing: false });
  }

  private handleOperationError(error: unknown, transferId?: string): void {
    if (error instanceof FileApiError && error.code === "UNAUTHORIZED") {
      this.deactivate();
      this.onUnauthorized();
      return;
    }
    const message = safeErrorMessage(error);
    if (transferId === undefined) this.emitError(message);
    else this.failLocal(transferId, message);
  }

  private emit(state: FileTransferUiState): void {
    this.state = state;
    this.onStateChange(state);
  }

  private isCurrent(generation: number, token: string): boolean {
    return this.generation === generation && this.token === token && this.state.kind === "active";
  }

  private resetOperations(): void {
    for (const controller of this.operations.values()) controller.abort();
    this.operations.clear();
  }
}

function toUiItem(item: FileSnapshotItem): FileTransferUiItem {
  return {
    id: item.metadata.transferId,
    metadata: item.metadata,
    status: item.status,
    bytesTransferred: item.bytesTransferred,
    speedBytesPerSecond: item.speedBytesPerSecond,
  };
}

function upsertBounded(
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
      };
  const next = existing === undefined
    ? [...items, safeItem]
    : items.map((candidate) => candidate.id === item.id ? safeItem : candidate);
  return next.length <= MAX_TRANSFER_ITEMS ? next : next.slice(next.length - MAX_TRANSFER_ITEMS);
}

function appendBounded(
  items: readonly FileTransferUiItem[],
  item: FileTransferUiItem,
): readonly FileTransferUiItem[] {
  const next = [...items, item];
  return next.length <= MAX_TRANSFER_ITEMS ? next : next.slice(next.length - MAX_TRANSFER_ITEMS);
}

function safeErrorMessage(error: unknown): string {
  if (error instanceof FileApiError) return error.message;
  return error instanceof Error && error.message.startsWith("Сеть")
    ? error.message
    : "Не удалось выполнить файловую операцию.";
}

function fileCodeMessage(code: FileErrorEvent["code"]): string {
  switch (code) {
    case "FILE_TOO_LARGE": return "Файл превышает лимит 1 ГиБ.";
    case "CHECKSUM_MISMATCH": return "Контрольная сумма файла не совпала.";
    case "NOT_APPROVED": return "Подтвердите передачу на телефоне.";
    case "CANCELLED": return "Передача отменена.";
    case "SESSION_UNAVAILABLE":
    case "UNAUTHORIZED": return "Сессия браузера завершена.";
    case "STREAM_FAILED": return "Поток передачи был прерван.";
    case "MESSAGE_CONFLICT": return "Команда передачи конфликтует с предыдущей.";
    case "UNSUPPORTED_VERSION": return "Версия file protocol не поддерживается.";
    case "INVALID_PAYLOAD": return "Некорректная файловая операция.";
  }
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

function selectionError(file: File): string | undefined {
  if (!Number.isSafeInteger(file.size) || file.size < 0) {
    return "Не удалось определить размер файла.";
  }
  if (file.size > HARD_MAX_FILE_BYTES) return "Файл превышает лимит 1 ГиБ.";
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
