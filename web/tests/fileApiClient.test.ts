import { describe, expect, it, vi } from "vitest";
import { FileApiClient, FileApiError, type FileOfferCommand } from "../src/fileApiClient";

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

  it("verifies and cancels through their protected endpoints", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(snapshot("COMPLETED")))
      .mockResolvedValueOnce(jsonResponse(snapshot("CANCELLED")));
    const client = new FileApiClient(fetcher);

    await client.verify("token", "transfer-1", "verify-1", 1_000, 4, "a".repeat(64));
    await client.cancel("token", "transfer-1");

    expect(fetcher.mock.calls[0]?.[0]).toBe("/api/v1/files/transfer-1/verify");
    expect(fetcher.mock.calls[1]?.[0]).toBe("/api/v1/transfers/transfer-1");
    expect(fetcher.mock.calls[1]?.[1]?.method).toBe("DELETE");
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
