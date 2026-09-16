import { createStreamingSha256 } from "../../../src/streamingSha256";

const input = requiredElement<HTMLInputElement>(
  "[data-testid=verification-file]",
);
const state = requiredElement<HTMLElement>("[data-testid=verification-state]");
const acknowledgements = requiredElement<HTMLElement>(
  "[data-testid=verification-acks]",
);
const expectedSize = Number(document.body.dataset.expectedSize);
const expectedSha256 = document.body.dataset.expectedSha256;

(globalThis as typeof globalThis & {
  runFileHashBenchmark?: typeof runFileHashBenchmark;
}).runFileHashBenchmark = runFileHashBenchmark;

input.addEventListener("change", () => {
  const file = input.files?.item(0);
  if (!file) return;
  void verify(file);
});

async function verify(file: File): Promise<void> {
  state.textContent = "verifying";
  if (file.size !== expectedSize) {
    state.textContent = "mismatch";
    return;
  }

  const digest = createStreamingSha256();
  const reader = file.stream().getReader();
  try {
    while (true) {
      const result = await reader.read();
      if (result.done) break;
      digest.update(result.value);
    }
  } finally {
    reader.releaseLock();
  }

  const sha256 = digest.digestHex();
  if (sha256 !== expectedSha256) {
    state.textContent = "mismatch";
    return;
  }

  const response = await fetch("/verify", {
    body: JSON.stringify({ sha256, size: file.size }),
    headers: { "Content-Type": "application/json" },
    method: "POST",
  });
  if (!response.ok) {
    state.textContent = "failed";
    return;
  }
  const result = (await response.json()) as { acknowledgements: number };
  acknowledgements.textContent = String(result.acknowledgements);
  state.textContent = "verified";
}

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

function requiredElement<T extends Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (!element) throw new Error(`Missing fixture element: ${selector}`);
  return element;
}
