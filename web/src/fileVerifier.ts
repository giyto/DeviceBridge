import { createStreamingSha256 } from "./streamingSha256";

export type FileVerificationResult =
  | Readonly<{ kind: "match"; sizeBytes: number; sha256: string }>
  | Readonly<{ kind: "size-mismatch"; actualSizeBytes: number }>
  | Readonly<{ kind: "checksum-mismatch"; actualSha256: string }>;

export async function verifyDownloadedFile(
  file: File,
  expectedSizeBytes: number,
  expectedSha256: string,
  onProgress: (bytesRead: number, totalBytes: number) => void = () => undefined,
  signal?: AbortSignal,
): Promise<FileVerificationResult> {
  if (file.size !== expectedSizeBytes) {
    return { kind: "size-mismatch", actualSizeBytes: file.size };
  }
  const sha256 = await hashFileStreaming(file, onProgress, signal);
  if (sha256.toLowerCase() !== expectedSha256.toLowerCase()) {
    return { kind: "checksum-mismatch", actualSha256: sha256 };
  }
  return { kind: "match", sizeBytes: file.size, sha256 };
}

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
