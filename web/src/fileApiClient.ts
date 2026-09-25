import {
  isProtocolId,
  isRecord,
  requireJsonResponse,
  requireSessionToken,
} from "./protocolGuards";

export const FILE_PROTOCOL_VERSION = 1;
export const HARD_MAX_FILE_BYTES = 1_073_741_824;
/** Smaller uploads always start over; the server keeps no part of them. */
export const RESUMABLE_UPLOAD_MIN_BYTES = 8 * 1024 * 1024;

export type FileDirection = "ANDROID_TO_BROWSER" | "BROWSER_TO_ANDROID";
export type FileTransferStatus =
  | "QUEUED"
  | "CONNECTING"
  | "TRANSFERRING"
  | "VERIFYING"
  | "COMPLETED"
  | "CANCELLED"
  | "FAILED";
export type FileApiErrorCode =
  | "INVALID_PAYLOAD"
  | "UNSUPPORTED_VERSION"
  | "FILE_TOO_LARGE"
  | "MESSAGE_CONFLICT"
  | "SESSION_UNAVAILABLE"
  | "NOT_APPROVED"
  | "CHECKSUM_MISMATCH"
  | "DESTINATION_UNAVAILABLE"
  | "INSUFFICIENT_SPACE"
  | "SOURCE_UNAVAILABLE"
  | "CANCELLED"
  | "STREAM_FAILED"
  | "UNAUTHORIZED";

export interface FileMetadata {
  readonly transferId: string;
  readonly displayName: string;
  readonly sizeBytes: number;
  readonly mimeType: string;
  readonly sha256: string;
  readonly direction: FileDirection;
}

export interface FileSnapshotItem {
  readonly metadata: FileMetadata;
  readonly status: FileTransferStatus;
  readonly bytesTransferred: number;
  readonly speedBytesPerSecond: number;
  /** Set for a failed item that can continue from these bytes instead of starting over. */
  readonly resumableBytes?: number;
}

export interface FileSnapshotEvent {
  readonly protocolVersion: 1;
  readonly messageId: string;
  readonly type: "file.snapshot";
  readonly timestamp: number;
  readonly items: readonly FileSnapshotItem[];
}

export interface FileOfferCommand {
  readonly messageId: string;
  readonly timestamp: number;
  readonly batchId: string;
  readonly items: readonly FileMetadata[];
}

export interface FileDownloadGrant {
  readonly protocolVersion: 1;
  readonly messageId: string;
  readonly type: "file.download_grant";
  readonly timestamp: number;
  readonly transferId: string;
  readonly downloadPath: string;
  readonly expiresAt: number;
}

export interface FileUploadOffset {
  readonly protocolVersion: 1;
  readonly messageId: string;
  readonly type: "file.upload_offset";
  readonly timestamp: number;
  readonly transferId: string;
  readonly offsetBytes: number;
}

export interface FileProgressEvent {
  readonly protocolVersion: 1;
  readonly messageId: string;
  readonly type: "file.progress";
  readonly timestamp: number;
  readonly transferId: string;
  readonly status: FileTransferStatus;
  readonly bytesTransferred: number;
  readonly totalBytes: number;
  readonly speedBytesPerSecond: number;
  readonly resumableBytes?: number;
}

export interface FileOfferEvent {
  readonly protocolVersion: 1;
  readonly messageId: string;
  readonly type: "file.offer";
  readonly timestamp: number;
  readonly batchId: string;
  readonly items: readonly FileMetadata[];
}

export interface FileErrorEvent {
  readonly protocolVersion: 1;
  readonly messageId: string;
  readonly type: "file.error";
  readonly timestamp: number;
  readonly relatedMessageId?: string;
  readonly transferId?: string;
  readonly code: FileApiErrorCode;
}

/** The transfer is over: it will not move again unless the person retries it. */
export function isTerminalStatus(status: FileTransferStatus): boolean {
  return status === "COMPLETED" || status === "CANCELLED" || status === "FAILED";
}

export class FileApiError extends Error {
  constructor(readonly status: number, readonly code: FileApiErrorCode) {
    super(fileErrorMessage(code));
    this.name = "FileApiError";
  }
}

export class FileApiClient {
  constructor(private readonly fetcher: typeof fetch = globalThis.fetch.bind(globalThis)) {}

  async offer(token: string, command: FileOfferCommand, signal?: AbortSignal): Promise<FileSnapshotEvent> {
    return parseFileSnapshot(await this.requestJson("/api/v1/files", token, {
      method: "POST",
      body: JSON.stringify({
        protocolVersion: FILE_PROTOCOL_VERSION,
        messageId: command.messageId,
        type: "file.offer",
        timestamp: command.timestamp,
        batchId: command.batchId,
        items: command.items,
      }),
      signal,
    }));
  }

  async requestDownloadGrant(
    token: string,
    transferId: string,
    messageId: string,
    timestamp: number,
    signal?: AbortSignal,
  ): Promise<FileDownloadGrant> {
    requireProtocolId(transferId);
    return parseDownloadGrant(await this.requestJson(
      `/api/v1/files/${encodeURIComponent(transferId)}/download-grant`, token, {
        method: "POST",
        body: JSON.stringify({
          protocolVersion: FILE_PROTOCOL_VERSION,
          messageId,
          type: "file.download_grant.request",
          timestamp,
        }),
        signal,
      },
    ));
  }

  /** Lets the phone prepare the output and tell how many bytes of the file it already has. */
  async requestUploadOffset(
    token: string,
    transferId: string,
    messageId: string,
    timestamp: number,
    signal?: AbortSignal,
  ): Promise<FileUploadOffset> {
    requireProtocolId(transferId);
    return parseUploadOffset(await this.requestJson(
      `/api/v1/files/${encodeURIComponent(transferId)}/upload-offset`, token, {
        method: "POST",
        body: JSON.stringify({
          protocolVersion: FILE_PROTOCOL_VERSION,
          messageId,
          type: "file.upload_offset.request",
          timestamp,
        }),
        signal,
      },
    ), transferId);
  }

  async cancel(token: string, transferId: string, signal?: AbortSignal): Promise<FileSnapshotEvent> {
    requireProtocolId(transferId);
    return parseFileSnapshot(await this.requestJson(
      `/api/v1/transfers/${encodeURIComponent(transferId)}`,
      token,
      { method: "DELETE", signal },
      false,
    ));
  }

  async retry(token: string, transferId: string, signal?: AbortSignal): Promise<FileSnapshotEvent> {
    requireProtocolId(transferId);
    return parseFileSnapshot(await this.requestJson(
      `/api/v1/transfers/${encodeURIComponent(transferId)}/retry`,
      token,
      { method: "POST", body: "{}", signal },
    ));
  }

  private async requestJson(
    url: string,
    token: string,
    init: RequestInit,
    hasBody = true,
  ): Promise<unknown> {
    requireSessionToken(token);
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${token}`);
    if (hasBody) headers.set("Content-Type", "application/json");
    const response = await this.fetcher(url, { ...init, headers });
    if (!response.ok) throw await parseApiError(response);
    requireJsonResponse(response, "DeviceBridge returned a non-JSON file response");
    return response.json() as Promise<unknown>;
  }
}

export function parseFileSnapshot(value: unknown): FileSnapshotEvent {
  const record = requireEnvelope(value, "file.snapshot");
  if (!Array.isArray(record.items) || record.items.length > 100) invalid();
  return {
    protocolVersion: 1,
    messageId: record.messageId as string,
    type: "file.snapshot",
    timestamp: record.timestamp as number,
    items: (record.items as unknown[]).map(parseSnapshotItem),
  };
}

export function parseFileOffer(value: unknown): FileOfferEvent {
  const record = requireEnvelope(value, "file.offer");
  if (!isProtocolId(record.batchId) || !Array.isArray(record.items) || record.items.length < 1 || record.items.length > 32) invalid();
  return {
    protocolVersion: 1,
    messageId: record.messageId as string,
    type: "file.offer",
    timestamp: record.timestamp as number,
    batchId: record.batchId as string,
    items: (record.items as unknown[]).map(parseMetadata),
  };
}

export function parseFileProgress(value: unknown): FileProgressEvent {
  const record = requireEnvelope(value, "file.progress");
  if (!isProtocolId(record.transferId) || !isTransferStatus(record.status)) invalid();
  const total = requireFileSize(record.totalBytes);
  const bytes = requireSafeNonNegativeInteger(record.bytesTransferred);
  const speed = requireSafeNonNegativeInteger(record.speedBytesPerSecond);
  if (bytes > total) invalid();
  const resumable = parseResumableBytes(record.resumableBytes, total);
  return {
    protocolVersion: 1,
    messageId: record.messageId as string,
    type: "file.progress",
    timestamp: record.timestamp as number,
    transferId: record.transferId as string,
    status: record.status,
    bytesTransferred: bytes,
    totalBytes: total,
    speedBytesPerSecond: speed,
    ...(resumable === undefined ? {} : { resumableBytes: resumable }),
  };
}

export function parseFileError(value: unknown): FileErrorEvent {
  const record = requireEnvelope(value, "file.error");
  if (!isFileApiErrorCode(record.code)) invalid();
  if (record.relatedMessageId !== undefined && !isProtocolId(record.relatedMessageId)) invalid();
  if (record.transferId !== undefined && !isProtocolId(record.transferId)) invalid();
  return {
    protocolVersion: 1,
    messageId: record.messageId as string,
    type: "file.error",
    timestamp: record.timestamp as number,
    code: record.code,
    ...(typeof record.relatedMessageId === "string" ? { relatedMessageId: record.relatedMessageId } : {}),
    ...(typeof record.transferId === "string" ? { transferId: record.transferId } : {}),
  };
}

function parseDownloadGrant(value: unknown): FileDownloadGrant {
  const record = requireEnvelope(value, "file.download_grant");
  if (!isProtocolId(record.transferId) || typeof record.downloadPath !== "string") invalid();
  if (!record.downloadPath.startsWith("/api/v1/files/") || record.downloadPath.includes("://") || record.downloadPath.length > 512) invalid();
  const expiresAt = requirePositiveInteger(record.expiresAt);
  if (expiresAt <= (record.timestamp as number)) invalid();
  return {
    protocolVersion: 1,
    messageId: record.messageId as string,
    type: "file.download_grant",
    timestamp: record.timestamp as number,
    transferId: record.transferId as string,
    downloadPath: record.downloadPath,
    expiresAt,
  };
}

export function parseUploadOffset(value: unknown, expectedTransferId: string): FileUploadOffset {
  const record = requireEnvelope(value, "file.upload_offset");
  if (record.transferId !== expectedTransferId) invalid();
  const offsetBytes = requireFileSize(record.offsetBytes);
  return {
    protocolVersion: 1,
    messageId: record.messageId as string,
    type: "file.upload_offset",
    timestamp: record.timestamp as number,
    transferId: expectedTransferId,
    offsetBytes,
  };
}

function parseSnapshotItem(value: unknown): FileSnapshotItem {
  if (!isRecord(value) || !isTransferStatus(value.status)) invalid();
  const metadata = parseMetadata(value.metadata);
  const bytes = requireSafeNonNegativeInteger(value.bytesTransferred);
  const speed = requireSafeNonNegativeInteger(value.speedBytesPerSecond);
  if (bytes > metadata.sizeBytes) invalid();
  const resumable = parseResumableBytes(value.resumableBytes, metadata.sizeBytes);
  return {
    metadata,
    status: value.status,
    bytesTransferred: bytes,
    speedBytesPerSecond: speed,
    ...(resumable === undefined ? {} : { resumableBytes: resumable }),
  };
}

/** Optional: servers before resumable transfers never send it. */
function parseResumableBytes(value: unknown, totalBytes: number): number | undefined {
  if (value === undefined || value === null) return undefined;
  const bytes = requireSafeNonNegativeInteger(value);
  if (bytes < 1 || bytes >= totalBytes) invalid();
  return bytes;
}

function parseMetadata(value: unknown): FileMetadata {
  if (!isRecord(value) || !isProtocolId(value.transferId)) invalid();
  if (typeof value.displayName !== "string" || value.displayName.trim().length === 0 || value.displayName.length > 255 || hasControl(value.displayName)) invalid();
  if (typeof value.mimeType !== "string" || value.mimeType.trim().length === 0 || value.mimeType.length > 127 || hasControl(value.mimeType)) invalid();
  if (typeof value.sha256 !== "string" || !/^[a-fA-F0-9]{64}$/.test(value.sha256)) invalid();
  if (!isDirection(value.direction)) invalid();
  return {
    transferId: value.transferId,
    displayName: value.displayName,
    sizeBytes: requireFileSize(value.sizeBytes),
    mimeType: value.mimeType,
    sha256: value.sha256.toLowerCase(),
    direction: value.direction,
  };
}

async function parseApiError(response: Response): Promise<FileApiError> {
  if (response.status === 401) return new FileApiError(401, "UNAUTHORIZED");
  try {
    const body = await response.json() as unknown;
    if (isRecord(body) && isFileApiErrorCode(body.code)) return new FileApiError(response.status, body.code);
    if (isRecord(body) && isRecord(body.error) && isFileApiErrorCode(body.error.code)) {
      return new FileApiError(response.status, body.error.code);
    }
  } catch { /* use status fallback */ }
  return new FileApiError(response.status, response.status === 413 ? "FILE_TOO_LARGE" : "INVALID_PAYLOAD");
}

function requireEnvelope(value: unknown, type: string): Record<string, unknown> {
  if (!isRecord(value) || value.protocolVersion !== 1 || value.type !== type || !isProtocolId(value.messageId)) invalid();
  requirePositiveInteger(value.timestamp);
  return value;
}

function requireProtocolId(value: string): void { if (!isProtocolId(value)) throw new Error("Invalid protocol identifier"); }
function requirePositiveInteger(value: unknown): number { const result = requireSafeNonNegativeInteger(value); if (result === 0) invalid(); return result; }
function requireSafeNonNegativeInteger(value: unknown): number { if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) invalid(); return value; }
function requireFileSize(value: unknown): number { const result = requireSafeNonNegativeInteger(value); if (result > HARD_MAX_FILE_BYTES) invalid(); return result; }
function isDirection(value: unknown): value is FileDirection { return value === "ANDROID_TO_BROWSER" || value === "BROWSER_TO_ANDROID"; }
function isTransferStatus(value: unknown): value is FileTransferStatus { return typeof value === "string" && ["QUEUED", "CONNECTING", "TRANSFERRING", "VERIFYING", "COMPLETED", "CANCELLED", "FAILED"].includes(value); }
function isFileApiErrorCode(value: unknown): value is FileApiErrorCode { return typeof value === "string" && ["INVALID_PAYLOAD", "UNSUPPORTED_VERSION", "FILE_TOO_LARGE", "MESSAGE_CONFLICT", "SESSION_UNAVAILABLE", "NOT_APPROVED", "CHECKSUM_MISMATCH", "DESTINATION_UNAVAILABLE", "INSUFFICIENT_SPACE", "SOURCE_UNAVAILABLE", "CANCELLED", "STREAM_FAILED", "UNAUTHORIZED"].includes(value); }
function hasControl(value: string): boolean { return [...value].some((character) => { const code = character.codePointAt(0); return code !== undefined && (code <= 0x1f || code === 0x7f); }); }
function invalid(): never { throw new Error("Invalid DeviceBridge file response"); }
export function fileErrorMessage(code: FileApiErrorCode): string {
  switch (code) {
    case "UNAUTHORIZED": return "Сессия браузера завершена.";
    case "FILE_TOO_LARGE": return "Файл превышает лимит 1 ГиБ.";
    case "CHECKSUM_MISMATCH": return "Контрольная сумма файла не совпала.";
    case "DESTINATION_UNAVAILABLE": return "Папка назначения недоступна.";
    case "INSUFFICIENT_SPACE": return "На устройстве недостаточно свободного места.";
    case "SOURCE_UNAVAILABLE": return "Исходный файл недоступен или изменился.";
    case "NOT_APPROVED": return "Передача ещё не подтверждена на телефоне.";
    case "CANCELLED": return "Передача отменена.";
    case "SESSION_UNAVAILABLE": return "Телефон или сессия сейчас недоступны.";
    case "MESSAGE_CONFLICT": return "Команда передачи конфликтует с предыдущей.";
    case "UNSUPPORTED_VERSION": return "Версия file protocol не поддерживается.";
    case "STREAM_FAILED": return "Поток передачи был прерван.";
    case "INVALID_PAYLOAD": return "Некорректная команда передачи файла.";
  }
}
