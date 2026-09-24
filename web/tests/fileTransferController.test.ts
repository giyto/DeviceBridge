import { describe, expect, it, vi } from "vitest";
import {
  FileTransferController,
  type FileDownloader,
  type FileUploader,
} from "../src/fileTransferController";
import {
  FileApiError,
  HARD_MAX_FILE_BYTES,
  RESUMABLE_UPLOAD_MIN_BYTES,
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

  it("keeps selected files and blocks confirmation while the session reconnects", async () => {
    const api = fakeApi();
    const states: unknown[] = [];
    const controller = createController(
      api,
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      states,
    );
    controller.activate("token");
    controller.selectFiles([new File(["draft"], "draft.txt", { type: "text/plain" })]);

    controller.setConnectionAvailable(false);
    await controller.confirmSelection();

    expect(api.offer).not.toHaveBeenCalled();
    expect(lastActive(states)).toMatchObject({
      connectionAvailable: false,
      selection: [{ displayName: "draft.txt" }],
    });
  });
  it("appends picker and drop files, deduplicates the same source, and keeps same-name files distinct", () => {
    const states: unknown[] = [];
    const controller = createController(
      fakeApi(),
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      states,
    );
    const original = new File(["one"], "same.txt", { type: "text/plain" });
    const differentSource = new File(["two"], "same.txt", { type: "text/plain" });
    controller.activate("token");

    controller.addFiles([original]);
    controller.addFiles([original, differentSource]);

    expect(lastActive(states).selection).toHaveLength(2);
    expect(lastActive(states).selection.map((item) => item.displayName))
      .toEqual(["same.txt", "same.txt"]);
  });

  it("removes one draft, clears all drafts, and keeps the draft when a picker is cancelled", () => {
    const states: unknown[] = [];
    const controller = createController(
      fakeApi(),
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      states,
    );
    controller.activate("token");
    controller.addFiles([new File(["one"], "one.txt"), new File(["two"], "two.txt")]);
    const firstKey = lastActive(states).selection[0]!.key;

    controller.addFiles([]);
    expect(lastActive(states).selection).toHaveLength(2);
    controller.removeDraft(firstKey);
    expect(lastActive(states).selection.map((item) => item.displayName)).toEqual(["two.txt"]);
    controller.clearDraft();
    expect(lastActive(states).selection).toEqual([]);
  });

  it("removes only server-accepted drafts and retains rejected drafts for retry", async () => {
    const api = fakeApi();
    api.offer.mockImplementationOnce(async (_token: string, command: FileOfferCommand) =>
      snapshotItems([[command.items[0]!.transferId, "CONNECTING"]]),
    );
    const states: unknown[] = [];
    const controller = createController(
      api,
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      states,
    );
    controller.activate("token");
    controller.addFiles([new File(["one"], "one.txt"), new File(["two"], "two.txt")]);

    await controller.confirmSelection();

    expect(lastActive(states).selection).toMatchObject([{
      displayName: "two.txt",
      error: "Телефон не принял этот файл. Его можно отправить повторно.",
    }]);
    expect(lastActive(states).transfers).toHaveLength(1);
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

  it("continues a large upload after the part the phone kept and reports progress from it", async () => {
    const api = fakeApi();
    const size = RESUMABLE_UPLOAD_MIN_BYTES + 1_000;
    const kept = RESUMABLE_UPLOAD_MIN_BYTES - 24;
    let answerOffset!: (offset: number) => void;
    api.requestUploadOffset.mockImplementation((_token: string, transferId: string) => new Promise((resolve) => {
      answerOffset = (offsetBytes) => resolve({
        protocolVersion: 1, messageId: "offset-1", type: "file.upload_offset", timestamp: 1_000,
        transferId, offsetBytes,
      });
    }));
    let reportProgress!: (bytes: number) => void;
    let finishUpload!: (value: FileSnapshotEvent) => void;
    const uploader = {
      upload: vi.fn().mockImplementation((
        _token: string, _id: string, _file: File, onProgress: (bytes: number) => void,
      ) => {
        reportProgress = onProgress;
        return new Promise<FileSnapshotEvent>((resolve) => { finishUpload = resolve; });
      }),
    };
    const states: unknown[] = [];
    const controller = createController(api, uploader, states);
    controller.activate("token");
    controller.selectFiles([new File([new Uint8Array(size)], "movie.mp4", { type: "video/mp4" })]);
    await controller.confirmSelection();
    const offered = api.offer.mock.calls[0]![1].items;
    const id = offered[0]!.transferId;

    controller.applySnapshot(snapshotOffered(offered, ["TRANSFERRING"]));
    controller.receiveProgress({ ...progress(id, "TRANSFERRING", 0), totalBytes: size });
    await vi.waitFor(() => expect(api.requestUploadOffset).toHaveBeenCalledOnce());
    expect(transferOf(states, id)).toMatchObject({ checkingSavedPart: true });
    expect(uploader.upload).not.toHaveBeenCalled();

    answerOffset(kept);
    await vi.waitFor(() => expect(uploader.upload).toHaveBeenCalledOnce());
    expect(uploader.upload.mock.calls[0]![5]).toBe(kept);
    expect(transferOf(states, id)).toMatchObject({
      checkingSavedPart: false,
      resumedFromBytes: kept,
      bytesTransferred: kept,
    });

    reportProgress(kept + 500);
    expect(transferOf(states, id)).toMatchObject({ bytesTransferred: kept + 500, resumedFromBytes: kept });

    finishUpload(snapshotOffered(offered, ["COMPLETED"]));
    await vi.waitFor(() => expect(transferOf(states, id)).toMatchObject({ status: "COMPLETED" }));
    expect(transferOf(states, id)?.resumedFromBytes).toBeUndefined();
  });

  it("marks a failed item the server can continue and keeps small uploads from checking a part", async () => {
    const api = fakeApi();
    const uploader = { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) };
    const states: unknown[] = [];
    const controller = createController(api, uploader, states);
    controller.activate("token");
    controller.selectFiles([new File(["one"], "one.txt")]);
    await controller.confirmSelection();
    const offered = api.offer.mock.calls[0]![1].items;
    const id = offered[0]!.transferId;

    controller.receiveProgress(progress(id, "TRANSFERRING", 0));
    await vi.waitFor(() => expect(uploader.upload).toHaveBeenCalledOnce());
    expect(states.some((state) =>
      (state as ReturnType<typeof lastActive>).transfers?.some((item) =>
        item.id === id && (item as { checkingSavedPart?: boolean }).checkingSavedPart === true),
    )).toBe(false);
    expect(uploader.upload.mock.calls[0]![5]).toBe(0);

    controller.receiveProgress({ ...progress(id, "TRANSFERRING", 0), status: "FAILED", resumableBytes: 2 } as never);
    expect(transferOf(states, id)).toMatchObject({ status: "FAILED", resumableBytes: 2 });
  });

  it("starts the upload when the phone approves before the offer answer arrives", async () => {
    const api = fakeApi();
    const uploader = { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) };
    const states: unknown[] = [];
    const controller = createController(api, uploader, states);
    let answerOffer!: () => void;
    api.offer.mockImplementation((_token: string, command: FileOfferCommand) => new Promise((resolve) => {
      answerOffer = () => resolve(snapshotOffered(command.items, ["CONNECTING"]));
    }));
    controller.activate("token");
    controller.selectFiles([new File(["one"], "one.txt")]);
    const confirming = controller.confirmSelection();
    await vi.waitFor(() => expect(api.offer).toHaveBeenCalledOnce());
    const id = api.offer.mock.calls[0]![1].items[0]!.transferId;

    // Auto-accept answered over the socket first; the card does not exist yet.
    controller.receiveProgress(progress(id, "TRANSFERRING", 0));
    expect(uploader.upload).not.toHaveBeenCalled();
    answerOffer();
    await confirming;

    await vi.waitFor(() => expect(uploader.upload).toHaveBeenCalledOnce());
  });

  it("starts the upload when the offer answer already says the phone approved it", async () => {
    const api = fakeApi();
    const uploader = { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) };
    const controller = createController(api, uploader, []);
    api.offer.mockImplementation(async (_token: string, command: FileOfferCommand) =>
      snapshotOffered(command.items, ["TRANSFERRING"]));
    controller.activate("token");
    controller.selectFiles([new File(["one"], "one.txt")]);

    await controller.confirmSelection();

    await vi.waitFor(() => expect(uploader.upload).toHaveBeenCalledOnce());
  });

  it("starts the continued upload when approval arrives while «Продолжить» is still running", async () => {
    const api = fakeApi();
    const uploader = { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) };
    const states: unknown[] = [];
    const controller = createController(api, uploader, states);
    controller.activate("token");
    controller.selectFiles([new File(["one"], "one.txt")]);
    await controller.confirmSelection();
    const offered = api.offer.mock.calls[0]![1].items;
    const id = offered[0]!.transferId;
    controller.applySnapshot(snapshotOffered(offered, ["FAILED"]));
    let answerRetry!: () => void;
    api.retry.mockImplementation(() => new Promise((resolve) => {
      answerRetry = () => resolve(snapshotOffered(offered, ["TRANSFERRING"]));
    }));

    const retrying = controller.retry(id);
    await vi.waitFor(() => expect(api.retry).toHaveBeenCalledOnce());
    controller.receiveProgress(progress(id, "TRANSFERRING", 0));
    expect(uploader.upload).not.toHaveBeenCalled();
    answerRetry();
    await retrying;

    await vi.waitFor(() => expect(uploader.upload).toHaveBeenCalledOnce());
  });

  it("marks the running upload as a network failure when the connection drops", async () => {
    const api = fakeApi();
    const uploader = {
      upload: vi.fn().mockImplementation((
        _token: string, _id: string, _file: File, _progress: unknown, signal: AbortSignal,
      ) => new Promise((_resolve, reject) => {
        signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
      })),
    };
    const states: unknown[] = [];
    const controller = createController(api, uploader, states);
    controller.activate("token");
    controller.selectFiles([new File(["one"], "one.txt")]);
    await controller.confirmSelection();
    const id = api.offer.mock.calls[0]![1].items[0]!.transferId;
    controller.receiveProgress(progress(id, "TRANSFERRING", 1));
    await vi.waitFor(() => expect(uploader.upload).toHaveBeenCalledOnce());

    controller.setConnectionAvailable(false);

    expect(transferOf(states, id)).toMatchObject({ status: "FAILED", localError: "Сеть прервала передачу файла." });
  });

  it("stops its upload when the phone cancels and keeps the card cancelled", async () => {
    const api = fakeApi();
    let reportProgress: ((bytes: number) => void) | undefined;
    let uploadSignal: AbortSignal | undefined;
    const uploader = {
      upload: vi.fn().mockImplementation((
        _token: string, _id: string, _file: File, onProgress: (bytes: number) => void, signal: AbortSignal,
      ) => new Promise((_resolve, reject) => {
        reportProgress = onProgress;
        uploadSignal = signal;
        signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
      })),
    };
    const states: unknown[] = [];
    const controller = createController(api, uploader, states);
    controller.activate("token");
    controller.selectFiles([new File(["one"], "one.txt")]);
    await controller.confirmSelection();
    const id = api.offer.mock.calls[0]![1].items[0]!.transferId;
    controller.receiveProgress(progress(id, "TRANSFERRING", 1));
    await vi.waitFor(() => expect(uploader.upload).toHaveBeenCalledOnce());

    controller.receiveProgress(terminalProgress(id, "CANCELLED"));
    reportProgress!(3);

    expect(uploadSignal!.aborted).toBe(true);
    expect(transferOf(states, id)).toMatchObject({ status: "CANCELLED", cancelledOnPhone: true });
    expect(lastActive(states)).not.toHaveProperty("error", expect.any(String));
  });

  it("drops the network error an upload got when the phone cut it for a cancel", async () => {
    const api = fakeApi();
    const uploader = {
      upload: vi.fn().mockRejectedValue(new Error("Сеть прервала передачу файла.")),
    };
    const states: unknown[] = [];
    const controller = createController(api, uploader, states);
    controller.activate("token");
    controller.selectFiles([new File(["one"], "one.txt")]);
    await controller.confirmSelection();
    const id = api.offer.mock.calls[0]![1].items[0]!.transferId;
    controller.receiveProgress(progress(id, "TRANSFERRING", 1));
    await vi.waitFor(() => expect(transferOf(states, id)).toMatchObject({ status: "FAILED" }));
    expect(lastActive(states)).toHaveProperty("error", "Сеть прервала передачу файла.");

    controller.receiveProgress(terminalProgress(id, "CANCELLED"));

    expect(transferOf(states, id)).toMatchObject({ status: "CANCELLED", cancelledOnPhone: true });
    expect(lastActive(states)).toHaveProperty("error", undefined);
  });

  it("shows the phone's «cancelled» answer as a cancel, not as an error", async () => {
    const api = fakeApi();
    const uploader = { upload: vi.fn().mockRejectedValue(new FileApiError(409, "CANCELLED")) };
    const states: unknown[] = [];
    const controller = createController(api, uploader, states);
    controller.activate("token");
    controller.selectFiles([new File(["one"], "one.txt")]);
    await controller.confirmSelection();
    const id = api.offer.mock.calls[0]![1].items[0]!.transferId;

    controller.receiveProgress(progress(id, "TRANSFERRING", 1));

    await vi.waitFor(() => expect(transferOf(states, id)).toMatchObject({ status: "CANCELLED" }));
    expect(transferOf(states, id)).toMatchObject({ cancelledOnPhone: true });
    expect(lastActive(states)).not.toHaveProperty("error", expect.any(String));
  });

  it("does not call a cancel from this browser one made on the phone", async () => {
    const api = fakeApi();
    const states: unknown[] = [];
    const controller = createController(api, { upload: vi.fn() }, states);
    controller.activate("token");
    controller.applySnapshot(snapshot("CONNECTING", "phone-file"));

    await controller.cancel("phone-file");
    controller.receiveProgress(terminalProgress("phone-file", "CANCELLED"));

    expect(transferOf(states, "phone-file")).toMatchObject({ status: "CANCELLED" });
    expect(transferOf(states, "phone-file")).not.toHaveProperty("cancelledOnPhone", true);
  });

  it("keeps an approval from the socket when the older retry answer still says «waiting»", async () => {
    const api = fakeApi();
    const uploader = { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) };
    const states: unknown[] = [];
    const controller = createController(api, uploader, states);
    controller.activate("token");
    controller.selectFiles([new File(["one"], "one.txt")]);
    await controller.confirmSelection();
    const offered = api.offer.mock.calls[0]![1].items;
    const id = offered[0]!.transferId;
    controller.applySnapshot(snapshotOffered(offered, ["CANCELLED"]));
    let answerRetry!: () => void;
    api.retry.mockImplementation(() => new Promise((resolve) => {
      // Written before auto-accept approved the retried item.
      answerRetry = () => resolve(snapshotOffered(offered, ["CONNECTING"]));
    }));

    const retrying = controller.retry(id);
    await vi.waitFor(() => expect(api.retry).toHaveBeenCalledOnce());
    controller.receiveProgress(progress(id, "TRANSFERRING", 0));
    answerRetry();
    await retrying;

    expect(transferOf(states, id)?.status).toBe("TRANSFERRING");
    await vi.waitFor(() => expect(uploader.upload).toHaveBeenCalledOnce());
  });

  it("shows how much of the source was re-checked before a retry and clears it afterwards", async () => {
    const api = fakeApi();
    const states: unknown[] = [];
    const controller = createController(
      api,
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      states,
      { start: vi.fn() },
      () => undefined,
      async (_file, onProgress) => {
        onProgress?.(1, 4);
        onProgress?.(2, 4);
        onProgress?.(2, 4);
        onProgress?.(4, 4);
        return "a".repeat(64);
      },
    );
    controller.activate("token");
    controller.selectFiles([new File(["abcd"], "report.bin")]);
    await controller.confirmSelection();
    const offered = api.offer.mock.calls[0]![1].items;
    const id = offered[0]!.transferId;
    controller.applySnapshot(snapshotOffered(offered, ["FAILED"]));
    const before = states.length;

    await controller.retry(id);

    const percents = (states.slice(before) as Array<ReturnType<typeof lastActive>>)
      .map((state) => (state.transfers?.find((item) => item.id === id) as { checkingSourcePercent?: number } | undefined)?.checkingSourcePercent)
      .filter((value) => value !== undefined);
    expect(percents).toEqual([0, 25, 50, 100]);
    expect(transferOf(states, id)).not.toHaveProperty("checkingSourcePercent", expect.anything());
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
    api.cancel.mockResolvedValueOnce(snapshotOffered(offered, ["CANCELLED", "CONNECTING"]));
    api.retry.mockResolvedValueOnce(snapshotOffered(offered, ["QUEUED", "CONNECTING"]));
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

  it("rejects retry when the original browser source hash changed", async () => {
    const api = fakeApi();
    const states: unknown[] = [];
    const hasher = vi.fn()
      .mockResolvedValueOnce("a".repeat(64))
      .mockResolvedValueOnce("b".repeat(64));
    const controller = createController(
      api,
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      states,
      { start: vi.fn() },
      () => undefined,
      hasher,
    );
    controller.activate("token");
    controller.selectFiles([
      new File(["data"], "report.bin", { type: "application/octet-stream" }),
    ]);
    await controller.confirmSelection();
    const transferId = api.offer.mock.calls[0]![1].items[0]!.transferId;
    api.cancel.mockResolvedValueOnce(snapshot("CANCELLED", transferId));
    await controller.cancel(transferId);

    await controller.retry(transferId);

    expect(hasher).toHaveBeenCalledTimes(2);
    expect(api.retry).not.toHaveBeenCalled();
    expect(lastActive(states).transfers).toMatchObject([
      {
        id: transferId,
        status: "FAILED",
        localError: "Исходный файл изменился. Выберите его заново.",
      },
    ]);
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

  it("uses the authorized effective limit for new browser drafts", async () => {
    const states: unknown[] = [];
    const controller = createController(
      fakeApi(),
      { upload: vi.fn().mockResolvedValue(snapshot("COMPLETED")) },
      states,
    );
    controller.activate("token", 4);

    controller.selectFiles([new File(["12345"], "five-bytes.txt")]);

    expect(lastActive(states).selection).toMatchObject([
      {
        displayName: "five-bytes.txt",
        error: "Файл превышает установленный лимит 4 Б.",
      },
    ]);
  });
});

function createController(
  api: ReturnType<typeof fakeApi>,
  uploader: FileUploader,
  states: unknown[],
  downloader: FileDownloader = { start: vi.fn() },
  onUnauthorized: () => void = () => undefined,
  hashFile: (
    file: File,
    onProgress?: (bytesRead: number, totalBytes: number) => void,
    signal?: AbortSignal,
  ) => Promise<string> = async (file) => file.name === "wrong.bin" ? "b".repeat(64) : "a".repeat(64),
) {
  let id = 0;
  return new FileTransferController(
    api, uploader, downloader,
    hashFile,
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
    requestUploadOffset: vi.fn().mockImplementation(async (_token: string, transferId: string) => ({
      protocolVersion: 1, messageId: "offset-1", type: "file.upload_offset", timestamp: 1_000,
      transferId, offsetBytes: 0,
    })),
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

function snapshotOffered(
  items: FileOfferCommand["items"],
  statuses: ReadonlyArray<FileSnapshotEvent["items"][number]["status"]>,
): FileSnapshotEvent {
  return {
    protocolVersion: 1,
    messageId: "snapshot-offered",
    type: "file.snapshot",
    timestamp: 1_000,
    items: items.map((item, index) => ({
      metadata: item,
      status: statuses[index]!,
      bytesTransferred: statuses[index] === "COMPLETED" ? item.sizeBytes : 0,
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

function terminalProgress(id: string, status: "CANCELLED" | "FAILED") {
  return {
    protocolVersion: 1 as const, messageId: "progress-end", type: "file.progress" as const,
    timestamp: 1_000, transferId: id, status, bytesTransferred: 1,
    totalBytes: 4, speedBytesPerSecond: 0,
  };
}

function transferOf(states: unknown[], id: string) {
  return (lastActive(states).transfers as Array<{
    id: string; status: string; bytesTransferred: number;
    checkingSavedPart?: boolean; resumedFromBytes?: number; resumableBytes?: number;
  }>).find((item) => item.id === id);
}

function lastActive(states: unknown[]) {
  return states.at(-1) as {
    kind: "active"; selection: Array<{ key: string; displayName: string; error?: string }>;
    transfers: Array<{ id: string; status: string; localError?: string }>;
  };
}
