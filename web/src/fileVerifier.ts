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
  try {
    while (true) {
      throwIfAborted(signal);
      const result = await reader.read();
      if (result.done) break;
      digest.update(result.value);
      bytesRead += result.value.byteLength;
      onProgress(bytesRead, file.size);
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

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted === true) {
    throw new DOMException("The operation was aborted", "AbortError");
  }
}
