import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";

export interface StreamingSha256 {
  update(chunk: Uint8Array): void;
  digestHex(): string;
}

export function createStreamingSha256(): StreamingSha256 {
  const hash = sha256.create();
  let completed = false;

  return {
    update(chunk) {
      if (completed) {
        throw new Error("SHA-256 digest is already finalized");
      }
      hash.update(chunk);
    },
    digestHex() {
      if (completed) {
        throw new Error("SHA-256 digest is already finalized");
      }
      completed = true;
      return bytesToHex(hash.digest());
    },
  };
}
