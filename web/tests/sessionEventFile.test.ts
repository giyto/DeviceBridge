import { describe, expect, it, vi } from "vitest";
import { SessionEventSocketClient, type SocketLike } from "../src/sessionEventSocketClient";

describe("SessionEventSocketClient file events", () => {
  it("strictly routes authenticated offer, progress, snapshot and error events", () => {
    const socket = new FakeSocket();
    const offer = vi.fn();
    const progress = vi.fn();
    const snapshot = vi.fn();
    const error = vi.fn();
    const client = new SessionEventSocketClient(
      () => socket,
      "http://devicebridge.local",
      { setTimeout: () => 1, clearTimeout: () => undefined },
      () => "client-1",
      () => 1_000,
    );
    client.connect("token", {
      onSessionLost: vi.fn(),
      onFileOffer: offer,
      onFileProgress: progress,
      onFileSnapshot: snapshot,
      onFileError: error,
    });
    socket.open();
    socket.message(fileOffer());
    expect(offer).not.toHaveBeenCalled();
    socket.message({
      protocolVersion: 1, messageId: "auth-1",
      type: "session.authenticated", timestamp: 1_000,
    });
    socket.message(fileOffer());
    socket.message(fileProgress());
    socket.message(fileSnapshot());
    socket.message({
      protocolVersion: 1, messageId: "error-1", type: "file.error", timestamp: 2_000,
      transferId: "file-1", code: "STREAM_FAILED",
    });

    expect(offer).toHaveBeenCalledOnce();
    expect(progress).toHaveBeenCalledWith(expect.objectContaining({ bytesTransferred: 2 }));
    expect(snapshot).toHaveBeenCalledWith(expect.objectContaining({ items: expect.any(Array) }));
    expect(error).toHaveBeenCalledWith(expect.objectContaining({ code: "STREAM_FAILED" }));
  });

  it("ignores malformed file events", () => {
    const socket = new FakeSocket();
    const progress = vi.fn();
    const client = new SessionEventSocketClient(
      () => socket,
      "http://devicebridge.local",
      { setTimeout: () => 1, clearTimeout: () => undefined },
    );
    client.connect("token", { onSessionLost: vi.fn(), onFileProgress: progress });
    socket.open();
    socket.message({
      protocolVersion: 1, messageId: "auth-1",
      type: "session.authenticated", timestamp: 1_000,
    });
    socket.message({ ...fileProgress(), bytesTransferred: 99, totalBytes: 4 });
    expect(progress).not.toHaveBeenCalled();
  });
});

class FakeSocket implements SocketLike {
  onopen: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  send(): void {}
  close(): void {}
  open(): void { this.onopen?.({} as Event); }
  message(value: unknown): void {
    this.onmessage?.({ data: JSON.stringify(value) } as MessageEvent<string>);
  }
}

function metadata() {
  return {
    transferId: "file-1", displayName: "report.bin", sizeBytes: 4,
    mimeType: "application/octet-stream", sha256: "a".repeat(64),
    direction: "ANDROID_TO_BROWSER",
  };
}
function fileOffer() {
  return {
    protocolVersion: 1, messageId: "offer-1", type: "file.offer", timestamp: 2_000,
    batchId: "batch-1", items: [metadata()],
  };
}
function fileProgress() {
  return {
    protocolVersion: 1, messageId: "progress-1", type: "file.progress", timestamp: 2_000,
    transferId: "file-1", status: "TRANSFERRING", bytesTransferred: 2,
    totalBytes: 4, speedBytesPerSecond: 2,
  };
}
function fileSnapshot() {
  return {
    protocolVersion: 1, messageId: "snapshot-1", type: "file.snapshot", timestamp: 2_000,
    items: [{ metadata: metadata(), status: "CONNECTING", bytesTransferred: 0, speedBytesPerSecond: 0 }],
  };
}
