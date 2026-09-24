import { createStreamingSha256 } from "./streamingSha256";

export async function hashFileStreaming(
  file: File,
  onProgress: (bytesRead: number, totalBytes: number) => void = () => undefined,
  signal?: AbortSignal,
): Promise<string> {
  throwIfAborted(signal);
  const digest = createStreamingSha256();
  const reader = file.stream().getReader();
  let bytesRead = 0;
  let sinceYield = 0;
  try {
    while (true) {
      throwIfAborted(signal);
      const result = await reader.read();
      if (result.done) break;
      digest.update(result.value);
      bytesRead += result.value.byteLength;
      sinceYield += result.value.byteLength;
      onProgress(bytesRead, file.size);
      if (sinceYield >= YIELD_EVERY_BYTES) {
        // Chunks arrive as microtasks; without a macrotask pause the page would not repaint.
        sinceYield = 0;
        await new Promise<void>((resolve) => setTimeout(resolve, 0));
      }
    }
    throwIfAborted(signal);
    if (bytesRead !== file.size) {
      throw new Error("Размер прочитанного файла изменился во время проверки.");
    }
    if (file.size === 0) onProgress(0, 0);
    return digest.digestHex();
  } finally {
    if (signal?.aborted === true) await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

const YIELD_EVERY_BYTES = 4 * 1024 * 1024;

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted === true) {
    throw new DOMException("The operation was aborted", "AbortError");
  }
}
