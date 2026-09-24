import {
  FileApiError,
  parseFileError,
  parseFileSnapshot,
  type FileSnapshotEvent,
} from "./fileApiClient";

export interface XhrUploadTargetLike {
  onprogress: ((event: ProgressEvent) => void) | null;
}

export interface XhrLike {
  readonly upload: XhrUploadTargetLike;
  readonly status: number;
  readonly responseText: string;
  onload: ((event: ProgressEvent<EventTarget>) => void) | null;
  onerror: ((event: ProgressEvent<EventTarget>) => void) | null;
  onabort: ((event: ProgressEvent<EventTarget>) => void) | null;
  open(method: string, url: string): void;
  setRequestHeader(name: string, value: string): void;
  send(body?: Document | XMLHttpRequestBodyInit | null): void;
  abort(): void;
}

type XhrFactory = () => XhrLike;

const UPLOAD_OFFSET_HEADER = "X-DeviceBridge-Upload-Offset";

export class XhrFileUploader {
  constructor(private readonly createXhr: XhrFactory = () => new XMLHttpRequest()) {}

  upload(
    token: string,
    transferId: string,
    file: File,
    onProgress: (bytesTransferred: number, totalBytes: number) => void,
    signal?: AbortSignal,
    offsetBytes = 0,
  ): Promise<FileSnapshotEvent> {
    requireToken(token);
    requireTransferId(transferId);
    if (!Number.isSafeInteger(offsetBytes) || offsetBytes < 0 || offsetBytes > file.size) {
      throw new Error("Invalid upload offset");
    }
    const xhr = this.createXhr();
    let lastBytes = offsetBytes;

    return new Promise((resolve, reject) => {
      let settled = false;
      const cleanup = (): void => signal?.removeEventListener("abort", abort);
      const finish = (action: () => void): void => {
        if (settled) return;
        settled = true;
        cleanup();
        action();
      };
      const abort = (): void => {
        xhr.abort();
      };

      xhr.open("POST", `/api/v1/files/${encodeURIComponent(transferId)}`);
      xhr.setRequestHeader("Authorization", `Bearer ${token}`);
      xhr.setRequestHeader("Content-Type", "application/octet-stream");
      xhr.setRequestHeader(UPLOAD_OFFSET_HEADER, String(offsetBytes));
      xhr.upload.onprogress = (event) => {
        const bounded = Math.min(file.size, Math.max(lastBytes, offsetBytes + event.loaded));
        lastBytes = bounded;
        onProgress(bounded, file.size);
      };
      xhr.onload = () => finish(() => {
        const body = parseJson(xhr.responseText);
        if (xhr.status < 200 || xhr.status >= 300) {
          try {
            const error = parseFileError(body);
            reject(new FileApiError(xhr.status, error.code));
          } catch {
            reject(new FileApiError(xhr.status, xhr.status === 401 ? "UNAUTHORIZED" : "STREAM_FAILED"));
          }
          return;
        }
        try {
          resolve(parseFileSnapshot(body));
        } catch (error) {
          reject(error);
        }
      });
      xhr.onerror = () => finish(() => reject(new Error("Сеть прервала передачу файла.")));
      xhr.onabort = () => finish(() => reject(new DOMException("The operation was aborted", "AbortError")));
      signal?.addEventListener("abort", abort, { once: true });
      if (signal?.aborted === true) {
        abort();
        return;
      }
      // Only the bytes after the part the server already keeps.
      xhr.send(offsetBytes > 0 ? file.slice(offsetBytes) : file);
    });
  }
}

function parseJson(value: string): unknown {
  try {
    return JSON.parse(value) as unknown;
  } catch {
    return undefined;
  }
}

function requireToken(token: string): void {
  if (token.length < 1 || token.length > 256 || /\s/.test(token)) {
    throw new Error("Invalid session credential");
  }
}

function requireTransferId(transferId: string): void {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(transferId)) {
    throw new Error("Invalid transfer identifier");
  }
}
