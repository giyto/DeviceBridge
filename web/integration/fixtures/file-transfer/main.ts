import { createStreamingSha256 } from "../../../src/streamingSha256";

(globalThis as typeof globalThis & {
  runFileHashBenchmark?: typeof runFileHashBenchmark;
}).runFileHashBenchmark = runFileHashBenchmark;

async function runFileHashBenchmark(sizeMiB: number): Promise<{
  digest: string;
  elapsedMs: number;
  heapGrowthBytes: number;
}> {
  const chunk = new Uint8Array(1024 * 1024);
  for (let index = 0; index < chunk.length; index += 1) {
    chunk[index] = (index * 31 + 17) & 0xff;
  }
  const memory = (
    performance as Performance & {
      memory?: { usedJSHeapSize: number };
    }
  ).memory;
  const heapBefore = memory?.usedJSHeapSize ?? 0;
  let peakHeap = heapBefore;
  const startedAt = performance.now();
  const digest = createStreamingSha256();

  for (let index = 0; index < sizeMiB; index += 1) {
    digest.update(chunk);
    if (index % 25 === 0) {
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
      peakHeap = Math.max(peakHeap, memory?.usedJSHeapSize ?? heapBefore);
    }
  }

  return {
    digest: digest.digestHex(),
    elapsedMs: performance.now() - startedAt,
    heapGrowthBytes: Math.max(0, peakHeap - heapBefore),
  };
}
