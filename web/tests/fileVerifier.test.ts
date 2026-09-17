import { describe, expect, it, vi } from "vitest";
import { hashFileStreaming } from "../src/fileVerifier";

describe("fileVerifier", () => {
  it("hashes selected upload bytes through bounded streaming SHA-256", async () => {
    const file = new File(["abc"], "upload.bin");
    const progress = vi.fn();

    const result = await hashFileStreaming(file, progress);

    expect(result).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    expect(progress).toHaveBeenLastCalledWith(3, 3);
  });

  it("cancels an in-progress stream without acknowledging it", async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(hashFileStreaming(new File(["abc"], "x.bin"), vi.fn(), controller.signal))
      .rejects.toMatchObject({ name: "AbortError" });
  });
});
