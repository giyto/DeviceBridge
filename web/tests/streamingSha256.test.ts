import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { createStreamingSha256 } from "../src/streamingSha256";

describe("StreamingSha256", () => {
  it.each([
    ["empty", new Uint8Array(), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"],
    ["abc", new TextEncoder().encode("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"],
  ])("matches the SHA-256 %s vector", (_name, input, expected) => {
    const digest = createStreamingSha256();
    digest.update(input);

    expect(digest.digestHex()).toBe(expected);
  });

  it.each([1, 7, 63, 64, 65, 4_096])(
    "produces the same digest with %i-byte chunk boundaries",
    (chunkSize) => {
      const input = deterministicBytes(131_071);
      const expected = createHash("sha256").update(input).digest("hex");
      const digest = createStreamingSha256();

      for (let offset = 0; offset < input.byteLength; offset += chunkSize) {
        digest.update(input.subarray(offset, offset + chunkSize));
      }

      expect(digest.digestHex()).toBe(expected);
    },
  );

  it(
    "hashes a deterministic 500 MiB stream without retaining proportional heap",
    () => {
      const chunk = deterministicBytes(1024 * 1024);
      const expected = createHash("sha256");
      const digest = createStreamingSha256();
      const heapBefore = process.memoryUsage().heapUsed;
      let peakHeap = heapBefore;

      for (let index = 0; index < 500; index += 1) {
        expected.update(chunk);
        digest.update(chunk);
        if (index % 25 === 0) {
          peakHeap = Math.max(peakHeap, process.memoryUsage().heapUsed);
        }
      }

      expect(digest.digestHex()).toBe(expected.digest("hex"));
      expect(peakHeap - heapBefore).toBeLessThan(96 * 1024 * 1024);
    },
    30_000,
  );
});

function deterministicBytes(size: number): Uint8Array {
  const bytes = new Uint8Array(size);
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = (index * 31 + 17) & 0xff;
  }
  return bytes;
}
