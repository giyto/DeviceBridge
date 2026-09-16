import { describe, expect, it, vi } from "vitest";
import {
  FileTransferController,
  type FileDownloader,
  type FileUploader,
} from "../src/fileTransferController";
import {
  FileApiError,
  HARD_MAX_FILE_BYTES,
  type FileSnapshotEvent,
} from "../src/fileApiClient";

describe("FileTransferController", () => {
  it("previews multiple files without auto-upload and starts only after confirmation", async () => {
    const api = fakeApi();
    const uploader = { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) };
    const states: unknown[] = [];
    const controller = createController(api, uploader, states);
    controller.activate("token");
    controller.selectFiles([
      new File(["one"], "one.txt", { type: "text/plain" }),
      new File(["two"], "two.txt", { type: "text/plain" }),
    ]);

    expect(api.offer).not.toHaveBeenCalled();
    expect(uploader.upload).not.toHaveBeenCalled();
    expect(lastActive(states).selection).toHaveLength(2);

    await controller.confirmSelection();
    expect(api.offer).toHaveBeenCalledOnce();
    expect(api.offer.mock.calls[0]?.[1].items).toHaveLength(2);
    expect(uploader.upload).not.toHaveBeenCalled();
  });

  it("starts raw upload on approved progress, deduplicates snapshots and preserves queue state", async () => {
    const api = fakeApi();
    const uploader = { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) };
    const states: unknown[] = [];
    const controller = createController(api, uploader, states);
    controller.activate("token");
    controller.selectFiles([new File(["one"], "one.txt")]);
    await controller.confirmSelection();
    const id = api.offer.mock.calls[0]![1].items[0]!.transferId;

    controller.applySnapshot(snapshot("CONNECTING", id));
    controller.applySnapshot(snapshot("CONNECTING", id));
    controller.receiveProgress(progress(id, "TRANSFERRING", 0));
    await vi.waitFor(() => expect(uploader.upload).toHaveBeenCalledOnce());

    expect(lastActive(states).transfers.filter((item) => item.id === id)).toHaveLength(1);
  });

  it("uses one-time native download and acknowledges verification after a match", async () => {
    const api = fakeApi();
    const downloader = { start: vi.fn() };
    const controller = createController(
      api,
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      [],
      downloader,
    );
    controller.activate("token");
    controller.receiveOffer({
      protocolVersion: 1, messageId: "offer-phone", type: "file.offer", timestamp: 1_000,
      batchId: "batch-phone", items: [metadata("phone-file", "ANDROID_TO_BROWSER")],
    });

    await controller.download("phone-file");
    expect(downloader.start).toHaveBeenCalledWith(
      "/api/v1/files/phone-file?grant=one-time", "report.bin",
    );

    await controller.verifyDownloaded("phone-file", new File(["data"], "report.bin"));
    expect(api.verify).toHaveBeenCalledOnce();
  });

  it("reports size and checksum mismatch to the server before showing failed", async () => {
    const api = fakeApi();
    api.verify.mockRejectedValue(new FileApiError(422, "CHECKSUM_MISMATCH"));
    const states: unknown[] = [];
    const controller = createController(
      api,
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      states,
    );
    controller.activate("token");
    controller.receiveOffer({
      protocolVersion: 1, messageId: "offer-phone", type: "file.offer", timestamp: 1_000,
      batchId: "batch-phone", items: [metadata("phone-file", "ANDROID_TO_BROWSER")],
    });

    await controller.verifyDownloaded("phone-file", new File(["bad!"], "wrong.bin"));

    expect(api.verify).toHaveBeenCalledOnce();
    expect(lastActive(states).transfers[0]?.status).toBe("FAILED");
  });

  it("cancels operations, aborts active work on session loss and keeps text independent", async () => {
    const api = fakeApi();
    const controller = createController(
      api,
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      [],
    );
    controller.activate("token");
    controller.receiveOffer({
      protocolVersion: 1, messageId: "offer-phone", type: "file.offer", timestamp: 1_000,
      batchId: "batch-phone", items: [metadata("phone-file", "ANDROID_TO_BROWSER")],
    });
    await controller.cancel("phone-file");
    expect(api.cancel).toHaveBeenCalledWith("token", "phone-file", expect.any(AbortSignal));
    controller.deactivate();
    expect(controller.currentState()).toEqual({ kind: "inactive" });
  });

  it("deactivates file state and reports an unauthorized API response to the session", async () => {
    const api = fakeApi();
    api.offer.mockRejectedValueOnce(new FileApiError(401, "UNAUTHORIZED"));
    const onUnauthorized = vi.fn();
    const controller = createController(
      api,
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      [],
      { start: vi.fn() },
      onUnauthorized,
    );
    controller.activate("expired-token");
    controller.selectFiles([new File(["one"], "one.txt")]);

    await controller.confirmSelection();

    expect(controller.currentState()).toEqual({ kind: "inactive" });
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it("rejects oversized and unsupported metadata before hashing or offering", async () => {
    const api = fakeApi();
    const states: unknown[] = [];
    const controller = createController(
      api,
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      states,
    );
    const oversized = new File([], "huge.bin");
    Object.defineProperty(oversized, "size", { value: HARD_MAX_FILE_BYTES + 1 });
    const unsupported = new File([], "x".repeat(256));
    controller.activate("token");

    controller.selectFiles([new File(["ok"], "ok.txt"), oversized, unsupported]);
    expect(lastActive(states).selection.filter((item) => item.error !== undefined)).toHaveLength(2);

    await controller.confirmSelection();
    expect(api.offer).toHaveBeenCalledOnce();
    expect(
      api.offer.mock.calls[0]?.[1].items.map((item: { displayName: string }) => item.displayName),
    ).toEqual(["ok.txt"]);
  });
});

function createController(
  api: ReturnType<typeof fakeApi>,
  uploader: FileUploader,
  states: unknown[],
  downloader: FileDownloader = { start: vi.fn() },
  onUnauthorized: () => void = () => undefined,
) {
  let id = 0;
  return new FileTransferController(
    api, uploader, downloader,
    async (file) => file.name === "wrong.bin" ? "b".repeat(64) : "a".repeat(64),
    (state) => states.push(state),
    () => `id-${++id}`,
    () => 1_000,
    onUnauthorized,
  );
}

function fakeApi() {
  return {
    offer: vi.fn().mockImplementation(async (_token, command) => snapshot("CONNECTING", command.items[0].transferId)),
    requestDownloadGrant: vi.fn().mockResolvedValue({
      protocolVersion: 1, messageId: "grant-1", type: "file.download_grant", timestamp: 1_000,
      transferId: "phone-file", downloadPath: "/api/v1/files/phone-file?grant=one-time", expiresAt: 31_000,
    }),
    verify: vi.fn().mockResolvedValue(snapshot("COMPLETED", "phone-file")),
    cancel: vi.fn().mockResolvedValue(snapshot("CANCELLED", "phone-file")),
  };
}

function metadata(id: string, direction: "ANDROID_TO_BROWSER" | "BROWSER_TO_ANDROID") {
  return {
    transferId: id, displayName: "report.bin", sizeBytes: 4, mimeType: "application/octet-stream",
    sha256: "a".repeat(64), direction,
  } as const;
}

function snapshot(status: string, id = "id-1"): FileSnapshotEvent {
  return {
    protocolVersion: 1, messageId: "snapshot-1", type: "file.snapshot", timestamp: 1_000,
    items: [{
      metadata: metadata(id, id === "phone-file" ? "ANDROID_TO_BROWSER" : "BROWSER_TO_ANDROID"),
      status: status as FileSnapshotEvent["items"][number]["status"],
      bytesTransferred: status === "COMPLETED" ? 4 : 0,
      speedBytesPerSecond: 0,
    }],
  };
}

function progress(id: string, status: "TRANSFERRING", bytes: number) {
  return {
    protocolVersion: 1 as const, messageId: "progress-1", type: "file.progress" as const,
    timestamp: 1_000, transferId: id, status, bytesTransferred: bytes,
    totalBytes: 4, speedBytesPerSecond: 0,
  };
}

function lastActive(states: unknown[]) {
  return states.at(-1) as {
    kind: "active"; selection: Array<{ error?: string }>;
    transfers: Array<{ id: string; status: string }>;
  };
}
