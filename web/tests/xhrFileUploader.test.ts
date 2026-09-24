import { describe, expect, it, vi } from "vitest";
import { XhrFileUploader, type XhrLike } from "../src/xhrFileUploader";

describe("XhrFileUploader", () => {
  it("uploads the original File with bearer header and monotonic progress", async () => {
    const xhr = new FakeXhr();
    const progress = vi.fn();
    const file = new File(["data"], "report.txt", { type: "text/plain" });
    const request = new XhrFileUploader(() => xhr).upload(
      "token", "transfer-1", file, progress,
    );

    expect(xhr.method).toBe("POST");
    expect(xhr.url).toBe("/api/v1/files/transfer-1");
    expect(xhr.headers.Authorization).toBe("Bearer token");
    expect(xhr.headers["Content-Type"]).toBe("application/octet-stream");
    expect(xhr.body).toBe(file);
    expect(xhr.headers["X-DeviceBridge-Upload-Offset"]).toBe("0");
    xhr.progress(3, 4);
    xhr.progress(2, 4);
    xhr.complete(200, snapshot("COMPLETED", 4));

    await expect(request).resolves.toMatchObject({ type: "file.snapshot" });
    expect(progress.mock.calls.map((call) => call[0])).toEqual([3, 3]);
  });

  it("sends only the bytes after the kept part and reports progress from the offset", async () => {
    const xhr = new FakeXhr();
    const progress = vi.fn();
    const file = new File(["0123456789"], "movie.bin");
    const request = new XhrFileUploader(() => xhr).upload(
      "token", "transfer-1", file, progress, undefined, 6,
    );

    expect(xhr.headers["X-DeviceBridge-Upload-Offset"]).toBe("6");
    expect(xhr.body).toBeInstanceOf(Blob);
    expect(await (xhr.body as Blob).text()).toBe("6789");
    xhr.progress(3, 4);
    xhr.progress(9, 4);
    xhr.complete(200, snapshot("COMPLETED", 4));

    await expect(request).resolves.toMatchObject({ type: "file.snapshot" });
    expect(progress.mock.calls.map((call) => call[0])).toEqual([9, 10]);
    expect(() => new XhrFileUploader(() => new FakeXhr()).upload(
      "token", "transfer-1", file, vi.fn(), undefined, 11,
    )).toThrow("Invalid upload offset");
  });

  it("aborts the network request and never reports a false completion", async () => {
    const xhr = new FakeXhr();
    const controller = new AbortController();
    const request = new XhrFileUploader(() => xhr).upload(
      "token", "transfer-1", new File(["data"], "x.bin"), vi.fn(), controller.signal,
    );
    controller.abort();

    expect(xhr.aborted).toBe(true);
    await expect(request).rejects.toMatchObject({ name: "AbortError" });
  });

  it("rejects network errors and malformed success responses", async () => {
    const failed = new FakeXhr();
    const networkRequest = new XhrFileUploader(() => failed).upload(
      "token", "transfer-1", new File(["x"], "x.bin"), vi.fn(),
    );
    failed.fail();
    await expect(networkRequest).rejects.toThrow("Сеть прервала передачу файла");

    const malformed = new FakeXhr();
    const malformedRequest = new XhrFileUploader(() => malformed).upload(
      "token", "transfer-1", new File(["x"], "x.bin"), vi.fn(),
    );
    malformed.complete(200, { invalid: true });
    await expect(malformedRequest).rejects.toThrow("Invalid DeviceBridge file response");
  });
});

class FakeUploadTarget {
  onprogress: ((event: ProgressEvent) => void) | null = null;
}

class FakeXhr implements XhrLike {
  readonly upload = new FakeUploadTarget();
  readonly headers: Record<string, string> = {};
  method = "";
  url = "";
  body?: Document | XMLHttpRequestBodyInit | null;
  status = 0;
  responseText = "";
  onload: ((event: ProgressEvent<EventTarget>) => void) | null = null;
  onerror: ((event: ProgressEvent<EventTarget>) => void) | null = null;
  onabort: ((event: ProgressEvent<EventTarget>) => void) | null = null;
  aborted = false;
  open(method: string, url: string): void { this.method = method; this.url = url; }
  setRequestHeader(name: string, value: string): void { this.headers[name] = value; }
  send(body?: Document | XMLHttpRequestBodyInit | null): void { this.body = body; }
  abort(): void { this.aborted = true; this.onabort?.({} as ProgressEvent<EventTarget>); }
  progress(loaded: number, total: number): void {
    this.upload.onprogress?.({ loaded, total, lengthComputable: true } as ProgressEvent);
  }
  complete(status: number, body: unknown): void {
    this.status = status;
    this.responseText = JSON.stringify(body);
    this.onload?.({} as ProgressEvent<EventTarget>);
  }
  fail(): void { this.onerror?.({} as ProgressEvent<EventTarget>); }
}

function snapshot(status: string, bytes: number) {
  return {
    protocolVersion: 1, messageId: "snapshot-1", type: "file.snapshot", timestamp: 2_000,
    items: [{
      metadata: {
        transferId: "transfer-1", displayName: "report.txt", sizeBytes: 4,
        mimeType: "text/plain", sha256: "a".repeat(64), direction: "BROWSER_TO_ANDROID",
      },
      status, bytesTransferred: bytes, speedBytesPerSecond: 0,
    }],
  };
}
