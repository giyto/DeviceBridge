import {
  FileApiError,
  HARD_MAX_FILE_BYTES,
  RESUMABLE_UPLOAD_MIN_BYTES,
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
const NETWORK_INTERRUPTED_MESSAGE = "Сеть прервала передачу файла.";

export interface FileSelectionPreview {
  readonly key: string;
  readonly displayName: string;
  readonly sizeBytes: number;
  readonly mimeType: string;
  readonly error?: string;
}

interface DraftFile {
  readonly key: string;
  readonly file: File;
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

export interface FileApi {
  offer: FileApiClient["offer"];
  requestDownloadGrant: FileApiClient["requestDownloadGrant"];
  cancel: FileApiClient["cancel"];
  retry: FileApiClient["retry"];
  requestUploadOffset: FileApiClient["requestUploadOffset"];
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
  private draftFiles: DraftFile[] = [];
  /** Files chosen before the phone went away, restored by the next [activate]. */
  private keptDraft?: DraftFile[];
  private readonly draftKeys = new WeakMap<File, string>();
  private draftSequence = 0;
  private effectiveFileLimitBytes = HARD_MAX_FILE_BYTES;
  private readonly sourceFiles = new Map<string, File>();
  private readonly operations = new Map<string, AbortController>();
  /** Transfers this browser asked to cancel; any other cancel came from the phone. */
  private readonly localCancels = new Set<string>();
  /** The transfer the section error is about, so a later cancel of it can clear the error. */
  private errorTransferId: string | undefined;
  /** Progress that arrived before the transfer's card: the phone can approve before the offer answer. */
  private readonly pendingProgress = new Map<string, FileProgressEvent>();

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

  activate(token: string, effectiveFileLimitBytes = HARD_MAX_FILE_BYTES): void {
    this.effectiveFileLimitBytes = Math.min(
      HARD_MAX_FILE_BYTES,
      Math.max(1, effectiveFileLimitBytes),
    );
    if (this.state.kind === "active" && this.token === token) return;
    this.resetOperations();
    this.generation += 1;
    this.token = token;
    this.draftFiles = this.keptDraft ?? [];
    this.keptDraft = undefined;
    this.sourceFiles.clear();
    this.emit({
      kind: "active",
      connectionAvailable: true,
      selection: this.selectionPreview(),
      transfers: [],
      preparing: false,
    });
  }

  /** Ends the session like [deactivate] but keeps the chosen files for the next session. */
  suspendSession(): void {
    const draft = this.draftFiles;
    this.deactivate();
    this.keptDraft = draft.length > 0 ? draft : undefined;
  }

  deactivate(): void {
    this.keptDraft = undefined;
    this.resetOperations();
    this.generation += 1;
    this.token = undefined;
    this.draftFiles = [];
    this.sourceFiles.clear();
    this.emit({ kind: "inactive" });
  }

  dispose(): void {
    this.deactivate();
  }

  setConnectionAvailable(available: boolean): void {
    if (this.state.kind !== "active" || this.state.connectionAvailable === available) return;
    let transfers = this.state.transfers;
    if (!available) {
      // Uploads cut by the lost connection end as a network failure, not as a silent abort.
      transfers = transfers.map((item) =>
        this.operations.has(item.id) &&
          item.metadata.direction === "BROWSER_TO_ANDROID" &&
          item.status === "TRANSFERRING"
          ? {
              ...item,
              status: "FAILED" as const,
              speedBytesPerSecond: 0,
              checkingSavedPart: undefined,
              localError: NETWORK_INTERRUPTED_MESSAGE,
            }
          : item,
      );
      this.resetOperations();
    }
    this.emit({
      ...this.state,
      transfers,
      connectionAvailable: available,
      preparing: available ? this.state.preparing : false,
    });
  }

  currentState(): FileTransferUiState {
    return this.state;
  }

  selectFiles(files: readonly File[]): void {
    this.addFiles(files);
  }

  addFiles(files: readonly File[]): void {
    if (this.state.kind !== "active") return;
    if (files.length === 0) return;
    let overflow = false;
    for (const file of files) {
      const existingKey = this.draftKeys.get(file);
      if (existingKey !== undefined && this.draftFiles.some((draft) => draft.key === existingKey)) {
        continue;
      }
      if (this.draftFiles.length >= MAX_BATCH_ITEMS) {
        overflow = true;
        continue;
      }
      const key = `draft-${++this.draftSequence}`;
      this.draftKeys.set(file, key);
      const error = selectionError(file, this.effectiveFileLimitBytes);
      this.draftFiles.push({ key, file, ...(error === undefined ? {} : { error }) });
    }
    this.emitDraft(overflow ? "В черновике может быть не более 32 файлов." : undefined);
  }

  removeDraft(key: string): void {
    if (this.state.kind !== "active" || this.state.preparing) return;
    const removed = this.draftFiles.find((draft) => draft.key === key);
    if (removed === undefined) return;
    this.draftFiles = this.draftFiles.filter((draft) => draft.key !== key);
    this.draftKeys.delete(removed.file);
    this.emitDraft();
  }

  clearDraft(): void {
    if (this.state.kind !== "active" || this.state.preparing || this.draftFiles.length === 0) return;
    for (const draft of this.draftFiles) this.draftKeys.delete(draft.file);
    this.draftFiles = [];
    this.emitDraft();
  }

  async confirmSelection(): Promise<void> {
    if (
      this.state.kind !== "active" ||
      !this.state.connectionAvailable ||
      this.token === undefined ||
      this.state.preparing ||
      this.draftFiles.every((draft) => draft.error !== undefined)
    ) return;
    const generation = this.generation;
    const token = this.token;
    const controller = new AbortController();
    this.operations.set("selection", controller);
    this.emit({ ...this.state, preparing: true, error: undefined });
    try {
      const prepared: Array<{ draft: DraftFile; metadata: FileMetadata }> = [];
      for (const draft of this.draftFiles.filter((item) => item.error === undefined)) {
        const file = draft.file;
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
        prepared.push({ draft, metadata });
      }
      const messageId = this.createId();
      const snapshot = await this.api.offer(token, {
        messageId,
        timestamp: this.now(),
        batchId: this.createId(),
        items: prepared.map((item) => item.metadata),
      }, controller.signal);
      if (!this.isCurrent(generation, token)) return;
      const acceptedIds = new Set(snapshot.items.map((item) => item.metadata.transferId));
      const acceptedDraftKeys = new Set<string>();
      for (const item of prepared) {
        if (acceptedIds.has(item.metadata.transferId)) {
          this.sourceFiles.set(item.metadata.transferId, item.draft.file);
          this.draftKeys.delete(item.draft.file);
          acceptedDraftKeys.add(item.draft.key);
        }
      }
      this.draftFiles = this.draftFiles
        .filter((draft) => !acceptedDraftKeys.has(draft.key))
        .map((draft) => prepared.some((item) => item.draft.key === draft.key)
          ? { ...draft, error: "Телефон не принял этот файл. Его можно отправить повторно." }
          : draft);
      this.applySnapshot(snapshot);
      if (this.state.kind === "active") {
        this.emit({
          ...this.state,
          selection: this.selectionPreview(),
          preparing: false,
          error: undefined,
        });
      }
      // Auto-accept may have approved the files before this answer was written.
      acceptedIds.forEach((transferId) => this.startUploadIfApproved(transferId));
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
    if (existing === undefined) {
      this.pendingProgress.delete(event.transferId);
      this.pendingProgress.set(event.transferId, event);
      if (this.pendingProgress.size > MAX_TRANSFER_ITEMS) {
        this.pendingProgress.delete(this.pendingProgress.keys().next().value!);
      }
      return;
    }
    const bytesTransferred = Math.max(existing.bytesTransferred, event.bytesTransferred);
    this.upsert({
      ...existing,
      status: event.status,
      bytesTransferred,
      speedBytesPerSecond: event.speedBytesPerSecond,
      localError: undefined,
      resumableBytes: event.resumableBytes,
      ...this.cancelOrigin(existing.id, event.status),
    });
    this.settleRemotely(existing.id, event.status);
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
      const existing = transfers.find((candidate) => candidate.id === item.metadata.transferId);
      // An answer can be older than a socket event already applied (auto-accept approves at once).
      if (existing !== undefined && isStaleStage(existing.status, item.status)) continue;
      transfers = upsertBounded(transfers, {
        ...toUiItem(item),
        ...this.cancelOrigin(item.metadata.transferId, item.status),
      });
    }
    this.emit({ ...this.state, transfers });
    for (const item of event.items) this.settleRemotely(item.metadata.transferId, item.status);
    for (const item of event.items) {
      const pending = this.pendingProgress.get(item.metadata.transferId);
      if (pending === undefined) continue;
      this.pendingProgress.delete(item.metadata.transferId);
      this.receiveProgress(pending);
    }
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
        this.upsert({
          ...existing,
          status: "FAILED",
          localError: fileCodeMessage(event.code, this.effectiveFileLimitBytes),
        });
      }
    }
    this.emitError(fileCodeMessage(event.code, this.effectiveFileLimitBytes));
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

  async cancel(transferId: string): Promise<void> {
    if (
      this.state.kind !== "active" ||
      !this.state.connectionAvailable ||
      this.token === undefined
    ) return;
    this.operations.get(transferId)?.abort();
    this.localCancels.add(transferId);
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
    if (
      this.state.kind !== "active" ||
      !this.state.connectionAvailable ||
      this.token === undefined
    ) return;
    const item = this.transfer(transferId);
    if (
      item === undefined ||
      item.status !== "FAILED" && item.status !== "CANCELLED"
    ) return;
    this.localCancels.delete(transferId);
    const sourceFile = item.metadata.direction === "BROWSER_TO_ANDROID"
      ? this.sourceFiles.get(transferId)
      : undefined;
    if (
      item.metadata.direction === "BROWSER_TO_ANDROID" &&
      sourceFile === undefined
    ) {
      this.failLocal(
        transferId,
        "Исходный файл больше недоступен. Выберите его заново.",
      );
      return;
    }
    const context = this.operationContext(transferId);
    if (context === undefined) return;
    try {
      if (sourceFile !== undefined) {
        if (!matchesSourceMetadata(sourceFile, item.metadata)) {
          this.sourceFiles.delete(transferId);
          this.failLocal(
            transferId,
            "Исходный файл изменился. Выберите его заново.",
          );
          return;
        }
        const showCheck = (percent: number | undefined): void => {
          const current = this.transfer(transferId);
          if (
            current !== undefined &&
            current.checkingSourcePercent !== percent &&
            this.isCurrent(context.generation, context.token)
          ) {
            this.upsert({ ...current, checkingSourcePercent: percent });
          }
        };
        showCheck(0);
        let currentSha256: string;
        try {
          currentSha256 = await this.hashFile(
            sourceFile,
            (bytesRead, totalBytes) => showCheck(
              totalBytes === 0 ? 100 : Math.floor(bytesRead * 100 / totalBytes),
            ),
            context.controller.signal,
          );
        } finally {
          showCheck(undefined);
        }
        if (!this.isCurrent(context.generation, context.token)) return;
        if (currentSha256.toLowerCase() !== item.metadata.sha256.toLowerCase()) {
          this.sourceFiles.delete(transferId);
          this.failLocal(
            transferId,
            "Исходный файл изменился. Выберите его заново.",
          );
          return;
        }
      }
      const snapshot = await this.api.retry(
        context.token,
        transferId,
        context.controller.signal,
      );
      if (this.isCurrent(context.generation, context.token)) {
        this.applySnapshot(snapshot);
      }
    } catch (error: unknown) {
      if (!isAbortError(error)) this.handleOperationError(error, transferId);
    } finally {
      this.finishOperation(transferId, context.controller);
    }
    // The approval may have arrived while the retry request still owned this transfer.
    if (this.isCurrent(context.generation, context.token)) this.startUploadIfApproved(transferId);
  }

  private startUploadIfApproved(transferId: string): void {
    const item = this.transfer(transferId);
    if (
      item?.metadata.direction === "BROWSER_TO_ANDROID" &&
      item.status === "TRANSFERRING"
    ) {
      this.startUpload(transferId);
    }
  }

  private startUpload(transferId: string): void {
    if (
      this.state.kind !== "active" ||
      !this.state.connectionAvailable ||
      this.token === undefined ||
      this.operations.has(transferId)
    ) return;
    const file = this.sourceFiles.get(transferId);
    if (file === undefined) return;
    const controller = new AbortController();
    this.operations.set(transferId, controller);
    void this.runUpload(transferId, file, this.token, this.generation, controller)
      .finally(() => this.finishOperation(transferId, controller));
  }

  /** Asks where to continue, then sends only the bytes the phone does not have yet. */
  private async runUpload(
    transferId: string,
    file: File,
    token: string,
    generation: number,
    controller: AbortController,
  ): Promise<void> {
    const update = (change: (item: FileTransferUiItem) => FileTransferUiItem): void => {
      const item = this.transfer(transferId);
      // Progress that was already on its way must not bring a finished card back.
      if (item === undefined || isTerminalStatus(item.status)) return;
      if (this.isCurrent(generation, token)) this.upsert(change(item));
    };
    try {
      if (file.size >= RESUMABLE_UPLOAD_MIN_BYTES) {
        update((item) => ({ ...item, checkingSavedPart: true }));
      }
      const { offsetBytes } = await this.api.requestUploadOffset(
        token,
        transferId,
        this.createId(),
        this.now(),
        controller.signal,
      );
      if (!this.isCurrent(generation, token)) return;
      update((item) => ({
        ...item,
        checkingSavedPart: false,
        resumedFromBytes: offsetBytes > 0 ? offsetBytes : undefined,
        bytesTransferred: Math.max(item.bytesTransferred, offsetBytes),
      }));
      const startedAt = this.now();
      const snapshot = await this.uploader.upload(
        token,
        transferId,
        file,
        (bytes) => update((item) => {
          const elapsedSeconds = Math.max(1, (this.now() - startedAt) / 1_000);
          return {
            ...item,
            status: "TRANSFERRING",
            bytesTransferred: Math.max(item.bytesTransferred, bytes),
            speedBytesPerSecond: Math.round(Math.max(0, bytes - offsetBytes) / elapsedSeconds),
          };
        }),
        controller.signal,
        offsetBytes,
      );
      if (this.isCurrent(generation, token)) this.applySnapshot(snapshot);
    } catch (error: unknown) {
      update((item) => ({ ...item, checkingSavedPart: false }));
      if (!isAbortError(error) && this.isCurrent(generation, token)) {
        this.handleOperationError(error, transferId);
      }
    }
  }

  private operationContext(transferId: string) {
    if (
      this.state.kind !== "active" ||
      !this.state.connectionAvailable ||
      this.token === undefined
    ) return undefined;
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
    this.errorTransferId = transferId;
  }

  private emitError(message: string): void {
    this.errorTransferId = undefined;
    if (this.state.kind === "active") this.emit({ ...this.state, error: message, preparing: false });
  }

  private emitDraft(error?: string): void {
    if (this.state.kind !== "active") return;
    this.emit({ ...this.state, selection: this.selectionPreview(), error });
  }

  private selectionPreview(): readonly FileSelectionPreview[] {
    return this.draftFiles.map((draft, index) => ({
      key: draft.key,
      displayName: draft.file.name || `file-${index + 1}`,
      sizeBytes: draft.file.size,
      mimeType: draft.file.type || "application/octet-stream",
      ...(draft.error === undefined ? {} : { error: draft.error }),
    }));
  }

  private handleOperationError(error: unknown, transferId?: string): void {
    if (error instanceof FileApiError && error.code === "UNAUTHORIZED") {
      this.deactivate();
      this.onUnauthorized();
      return;
    }
    if (transferId !== undefined) {
      const item = this.transfer(transferId);
      // The request lost a race with a cancel: the card already says what happened.
      if (item?.status === "CANCELLED") return;
      if (error instanceof FileApiError && error.code === "CANCELLED" && item !== undefined) {
        this.upsert({ ...item, status: "CANCELLED", ...this.cancelOrigin(transferId, "CANCELLED") });
        return;
      }
    }
    const message = safeErrorMessage(error);
    if (transferId === undefined) this.emitError(message);
    else this.failLocal(transferId, message);
  }

  /** Marks a cancel this browser did not ask for as one made on the phone. */
  private cancelOrigin(
    transferId: string,
    status: FileTransferStatus,
  ): Pick<FileTransferUiItem, "cancelledOnPhone"> {
    return status === "CANCELLED" && !this.localCancels.has(transferId)
      ? { cancelledOnPhone: true }
      : { cancelledOnPhone: undefined };
  }

  /**
   * The phone ended a transfer: stop what this browser still does for it, and drop an error
   * the lost race left behind.
   */
  private settleRemotely(transferId: string, status: FileTransferStatus): void {
    if (status !== "CANCELLED" && status !== "FAILED") return;
    const operation = this.operations.get(transferId);
    if (operation !== undefined) {
      this.operations.delete(transferId);
      operation.abort();
    }
    if (status === "CANCELLED" && this.errorTransferId === transferId && this.state.kind === "active") {
      this.errorTransferId = undefined;
      this.emit({ ...this.state, error: undefined });
    }
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
    this.pendingProgress.clear();
    this.localCancels.clear();
  }
}

function toUiItem(item: FileSnapshotItem): FileTransferUiItem {
  return {
    id: item.metadata.transferId,
    metadata: item.metadata,
    status: item.status,
    bytesTransferred: item.bytesTransferred,
    speedBytesPerSecond: item.speedBytesPerSecond,
    ...(item.resumableBytes === undefined ? {} : { resumableBytes: item.resumableBytes }),
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
        // Local upload stages outlive server updates until the item settles.
        checkingSavedPart: isSettled(item.status)
          ? undefined
          : item.checkingSavedPart ?? existing.checkingSavedPart,
        resumedFromBytes: isSettled(item.status)
          ? undefined
          : item.resumedFromBytes ?? existing.resumedFromBytes,
      };
  const next = existing === undefined
    ? [...items, safeItem]
    : items.map((candidate) => candidate.id === item.id ? safeItem : candidate);
  return next.length <= MAX_TRANSFER_ITEMS ? next : next.slice(next.length - MAX_TRANSFER_ITEMS);
}

const ACTIVE_STAGE_ORDER: ReadonlyArray<FileTransferStatus> = [
  "QUEUED",
  "CONNECTING",
  "TRANSFERRING",
  "VERIFYING",
];

/** Within one attempt an item only moves forward; an older answer must not move it back. */
function isStaleStage(current: FileTransferStatus, incoming: FileTransferStatus): boolean {
  const currentIndex = ACTIVE_STAGE_ORDER.indexOf(current);
  const incomingIndex = ACTIVE_STAGE_ORDER.indexOf(incoming);
  return currentIndex >= 0 && incomingIndex >= 0 && incomingIndex < currentIndex;
}

function isTerminalStatus(status: FileTransferStatus): boolean {
  return status === "COMPLETED" || status === "CANCELLED" || status === "FAILED";
}

function isSettled(status: FileTransferStatus): boolean {
  return status === "COMPLETED" || status === "CANCELLED" || status === "FAILED" || status === "QUEUED";
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

function fileCodeMessage(
  code: FileErrorEvent["code"],
  effectiveFileLimitBytes: number,
): string {
  switch (code) {
    case "FILE_TOO_LARGE": return fileLimitMessage(effectiveFileLimitBytes);
    case "CHECKSUM_MISMATCH": return "Контрольная сумма файла не совпала.";
    case "DESTINATION_UNAVAILABLE": return "Папка назначения недоступна.";
    case "INSUFFICIENT_SPACE": return "На устройстве недостаточно свободного места.";
    case "SOURCE_UNAVAILABLE": return "Исходный файл недоступен или изменился.";
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

function matchesSourceMetadata(file: File, metadata: FileMetadata): boolean {
  return file.size === metadata.sizeBytes &&
    (file.name || "file-" + metadata.transferId.slice(0, 8)) === metadata.displayName &&
    (file.type || "application/octet-stream") === metadata.mimeType;
}

function selectionError(file: File, effectiveFileLimitBytes: number): string | undefined {
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

function fileLimitMessage(bytes: number): string {
  const formatted = bytes >= 1024 ** 3
    ? `${bytes / 1024 ** 3} ГиБ`
    : bytes >= 1024 ** 2
      ? `${bytes / 1024 ** 2} МиБ`
      : bytes >= 1024
        ? `${bytes / 1024} КиБ`
        : `${bytes} Б`;
  return `Файл превышает установленный лимит ${formatted}.`;
}
