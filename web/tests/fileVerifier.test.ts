import { describe, expect, it, vi } from "vitest";
import { hashFileStreaming, verifyDownloadedFile } from "../src/fileVerifier";

describe("fileVerifier", () => {
  it("verifies selected downloaded bytes through bounded streaming SHA-256", async () => {
    const file = new File(["abc"], "download.bin");
    const progress = vi.fn();

    const result = await verifyDownloadedFile(
      file,
      3,
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      progress,
    );

    expect(result).toEqual({ kind: "match", sizeBytes: 3, sha256: expect.any(String) });
    expect(progress).toHaveBeenLastCalledWith(3, 3);
  });

  it("rejects size before reading and reports checksum mismatch", async () => {
    await expect(verifyDownloadedFile(new File(["abc"], "wrong.bin"), 4, "a".repeat(64)))
      .resolves.toEqual({ kind: "size-mismatch", actualSizeBytes: 3 });
    await expect(verifyDownloadedFile(new File(["abc"], "wrong.bin"), 3, "a".repeat(64)))
      .resolves.toMatchObject({ kind: "checksum-mismatch" });
  });

  it("cancels an in-progress stream without acknowledging it", async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(hashFileStreaming(new File(["abc"], "x.bin"), vi.fn(), controller.signal))
      .rejects.toMatchObject({ name: "AbortError" });
  });
});
