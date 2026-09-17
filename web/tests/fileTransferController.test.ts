import { describe, expect, it, vi } from "vitest";
import {
  FileTransferController,
  type FileDownloader,
  type FileUploader,
} from "../src/fileTransferController";
import {
  FileApiError,
  HARD_MAX_FILE_BYTES,
  type FileOfferCommand,
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

  it("uses a one-time native download without asking the user to reselect the file", async () => {
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

  it("retries a cancelled phone file with the same item instead of dropping it", async () => {
    const api = fakeApi();
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
    await controller.cancel("phone-file");

    await controller.retry("phone-file");

    expect(api.retry).toHaveBeenCalledWith("token", "phone-file", expect.any(AbortSignal));
    expect(api.offer).not.toHaveBeenCalled();
    expect(lastActive(states).transfers).toMatchObject([
      { id: "phone-file", status: "CONNECTING" },
    ]);
  });

  it("retries one cancelled browser file without recreating or losing the rest of its batch", async () => {
    const api = fakeApi();
    const states: unknown[] = [];
    const controller = createController(
      api,
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      states,
    );
    controller.activate("token");
    controller.selectFiles([
      new File(["one"], "one.txt"),
      new File(["two"], "two.txt"),
    ]);
    await controller.confirmSelection();
    const offered = api.offer.mock.calls[0]![1].items;
    const firstId = offered[0]!.transferId;
    const secondId = offered[1]!.transferId;
    api.cancel.mockResolvedValueOnce(snapshotItems([
      [firstId, "CANCELLED"],
      [secondId, "CONNECTING"],
    ]));
    api.retry.mockResolvedValueOnce(snapshotItems([
      [firstId, "QUEUED"],
      [secondId, "CONNECTING"],
    ]));
    await controller.cancel(firstId);

    await controller.retry(firstId);

    expect(api.offer).toHaveBeenCalledOnce();
    expect(api.retry).toHaveBeenCalledWith("token", firstId, expect.any(AbortSignal));
    expect(lastActive(states).transfers.map((item) => item.id)).toEqual([firstId, secondId]);
    expect(lastActive(states).transfers.map((item) => item.status)).toEqual(["QUEUED", "CONNECTING"]);
  });

  it("explains when a refreshed browser upload no longer has its source File", async () => {
    const api = fakeApi();
    const states: unknown[] = [];
    const controller = createController(
      api,
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      states,
    );
    controller.activate("token");
    controller.applySnapshot(snapshot("CANCELLED", "browser-file"));

    await controller.retry("browser-file");

    expect(api.retry).not.toHaveBeenCalled();
    expect(lastActive(states).transfers[0]).toMatchObject({
      id: "browser-file",
      status: "FAILED",
      localError: "Исходный файл больше недоступен. Выберите его заново.",
    });
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
    offer: vi.fn().mockImplementation(async (_token: string, command: FileOfferCommand) => snapshotItems(
      command.items.map((item, index) => [
        item.transferId,
        index === 0 ? "CONNECTING" : "QUEUED",
      ] as const),
    )),
    requestDownloadGrant: vi.fn().mockResolvedValue({
      protocolVersion: 1, messageId: "grant-1", type: "file.download_grant", timestamp: 1_000,
      transferId: "phone-file", downloadPath: "/api/v1/files/phone-file?grant=one-time", expiresAt: 31_000,
    }),
    cancel: vi.fn().mockResolvedValue(snapshot("CANCELLED", "phone-file")),
    retry: vi.fn().mockResolvedValue(snapshot("CONNECTING", "phone-file")),
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

function snapshotItems(
  entries: ReadonlyArray<readonly [string, string]>,
): FileSnapshotEvent {
  return {
    protocolVersion: 1, messageId: "snapshot-many", type: "file.snapshot", timestamp: 1_000,
    items: entries.map(([id, status]) => ({
      metadata: metadata(id, id === "phone-file" ? "ANDROID_TO_BROWSER" : "BROWSER_TO_ANDROID"),
      status: status as FileSnapshotEvent["items"][number]["status"],
      bytesTransferred: status === "COMPLETED" ? 4 : 0,
      speedBytesPerSecond: 0,
    })),
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
    transfers: Array<{ id: string; status: string; localError?: string }>;
  };
}
