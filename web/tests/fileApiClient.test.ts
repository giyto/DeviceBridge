import { describe, expect, it, vi } from "vitest";
import {
  FileApiClient,
  FileApiError,
  parseFileProgress,
  parseFileSnapshot,
  type FileOfferCommand,
} from "../src/fileApiClient";

const offer: FileOfferCommand = {
  messageId: "offer-1",
  timestamp: 1_000,
  batchId: "batch-1",
  items: [{
    transferId: "transfer-1",
    displayName: "report.pdf",
    sizeBytes: 4,
    mimeType: "application/pdf",
    sha256: "a".repeat(64),
    direction: "BROWSER_TO_ANDROID",
  }],
};

describe("FileApiClient", () => {
  it("offers metadata with bearer only in the header and strictly parses snapshot", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(snapshot()));
    const client = new FileApiClient(fetcher);

    const result = await client.offer("session-token", offer);

    expect(result.items[0]?.metadata.displayName).toBe("report.pdf");
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe("/api/v1/files");
    expect(String(init?.body)).not.toContain("session-token");
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer session-token");
    expect(JSON.parse(String(init?.body))).toMatchObject({
      protocolVersion: 1,
      type: "file.offer",
      batchId: "batch-1",
    });
  });

  it("requests the resume offset of an approved upload and strictly parses it", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      protocolVersion: 1,
      messageId: "offset-1",
      type: "file.upload_offset",
      timestamp: 2_000,
      transferId: "transfer-1",
      offsetBytes: 8_388_608,
    }));
    const client = new FileApiClient(fetcher);

    const result = await client.requestUploadOffset("session-token", "transfer-1", "offset-1", 1_000);

    expect(result.offsetBytes).toBe(8_388_608);
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe("/api/v1/files/transfer-1/upload-offset");
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer session-token");
    expect(JSON.parse(String(init?.body))).toMatchObject({
      protocolVersion: 1,
      messageId: "offset-1",
      type: "file.upload_offset.request",
    });

    for (const broken of [
      { offsetBytes: -1 },
      { offsetBytes: 1.5 },
      { transferId: "other-transfer" },
      { protocolVersion: 2 },
    ]) {
      fetcher.mockResolvedValueOnce(jsonResponse({
        protocolVersion: 1,
        messageId: "offset-1",
        type: "file.upload_offset",
        timestamp: 2_000,
        transferId: "transfer-1",
        offsetBytes: 0,
        ...broken,
      }));
      await expect(client.requestUploadOffset("session-token", "transfer-1", "offset-1", 1_000))
        .rejects.toThrow("Invalid DeviceBridge file response");
    }
  });

  it("reads the optional resumable size of failed items and rejects impossible values", () => {
    const item = (resumableBytes?: unknown) => ({
      metadata: {
        transferId: "transfer-1", displayName: "movie.mp4", sizeBytes: 100,
        mimeType: "video/mp4", sha256: "a".repeat(64), direction: "BROWSER_TO_ANDROID",
      },
      status: "FAILED", bytesTransferred: 40, speedBytesPerSecond: 0,
      ...(resumableBytes === undefined ? {} : { resumableBytes }),
    });
    const snapshotOf = (resumableBytes?: unknown) => ({
      protocolVersion: 1, messageId: "snapshot-1", type: "file.snapshot", timestamp: 1,
      items: [item(resumableBytes)],
    });
    const progressOf = (resumableBytes?: unknown) => ({
      protocolVersion: 1, messageId: "progress-1", type: "file.progress", timestamp: 1,
      transferId: "transfer-1", status: "FAILED", bytesTransferred: 40, totalBytes: 100,
      speedBytesPerSecond: 0, ...(resumableBytes === undefined ? {} : { resumableBytes }),
    });

    expect(parseFileSnapshot(snapshotOf(40)).items[0]!.resumableBytes).toBe(40);
    expect(parseFileSnapshot(snapshotOf()).items[0]).not.toHaveProperty("resumableBytes");
    expect(parseFileProgress(progressOf(40)).resumableBytes).toBe(40);
    expect(parseFileProgress(progressOf()).resumableBytes).toBeUndefined();
    for (const invalid of [0, 100, -1, 1.5, "40"]) {
      expect(() => parseFileSnapshot(snapshotOf(invalid))).toThrow();
      expect(() => parseFileProgress(progressOf(invalid))).toThrow();
    }
  });

  it("requests a scoped download grant and never appends bearer to its URL", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      protocolVersion: 1,
      messageId: "grant-1",
      type: "file.download_grant",
      timestamp: 2_000,
      transferId: "transfer-1",
      downloadPath: "/api/v1/files/transfer-1?grant=one-time",
      expiresAt: 32_000,
    }));

    const grant = await new FileApiClient(fetcher).requestDownloadGrant(
      "session-token", "transfer-1", "grant-1", 1_000,
    );

    expect(grant.downloadPath).toContain("grant=one-time");
    expect(grant.downloadPath).not.toContain("session-token");
    expect(fetcher.mock.calls[0]?.[0]).toBe("/api/v1/files/transfer-1/download-grant");
  });

  it("cancels and retries through protected transfer endpoints and has no manual verify API", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(snapshot("CANCELLED")))
      .mockResolvedValueOnce(jsonResponse(snapshot("CONNECTING")));
    const client = new FileApiClient(fetcher);

    await client.cancel("token", "transfer-1");
    await client.retry("token", "transfer-1");

    expect("verify" in client).toBe(false);
    expect(fetcher.mock.calls[0]?.[0]).toBe("/api/v1/transfers/transfer-1");
    expect(fetcher.mock.calls[0]?.[1]?.method).toBe("DELETE");
    expect(fetcher.mock.calls[1]?.[0]).toBe("/api/v1/transfers/transfer-1/retry");
    expect(fetcher.mock.calls[1]?.[1]?.method).toBe("POST");
  });

  it("rejects malformed successful responses instead of trusting them", async () => {
    const client = new FileApiClient(vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse({ ...snapshot(), items: [{ metadata: { displayName: "missing fields" } }] }),
    ));
    await expect(client.offer("token", offer)).rejects.toThrow("Invalid DeviceBridge file response");
  });

  it("returns safe typed unauthorized errors", async () => {
    const client = new FileApiClient(vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse({ error: { code: "UNAUTHORIZED", message: "internal detail" } }, 401),
    ));
    const error = await client.offer("token", offer).catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(FileApiError);
    expect(error).toMatchObject({ status: 401, code: "UNAUTHORIZED" });
    expect((error as Error).message).not.toContain("internal detail");
  });

  it.each([
    "DESTINATION_UNAVAILABLE",
    "INSUFFICIENT_SPACE",
    "SOURCE_UNAVAILABLE",
  ] as const)("parses the distinct %s file failure", async (code) => {
    const client = new FileApiClient(vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse({ error: { code } }, 409),
    ));
    const error = await client.offer("token", offer).catch((caught: unknown) => caught);
    expect(error).toMatchObject({ status: 409, code });
  });
});

function snapshot(status = "CONNECTING") {
  return {
    protocolVersion: 1,
    messageId: "snapshot-1",
    type: "file.snapshot",
    timestamp: 2_000,
    items: [{
      metadata: offer.items[0],
      status,
      bytesTransferred: status === "COMPLETED" ? 4 : 0,
      speedBytesPerSecond: 0,
    }],
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
