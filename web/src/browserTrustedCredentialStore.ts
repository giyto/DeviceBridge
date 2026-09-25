import { hasExactKeys } from "./versionedRecord";

const STORAGE_KEY = "devicebridge.trusted-browser.v1";
const FORMAT_VERSION = 1;
const MAX_CREDENTIAL_LENGTH = 256;
const CREDENTIAL_PATTERN = /^[A-Za-z0-9_-]+$/;

export interface BrowserTrustedCredential {
  readonly credential: string;
  readonly expiresAtEpochMillis: number;
}

export class BrowserTrustedCredentialStore {
  constructor(
    private readonly storage: Storage = globalThis.localStorage,
    private readonly nowEpochMillis: () => number = () => Date.now(),
  ) {}

  read(): BrowserTrustedCredential | undefined {
    const raw = this.storage.getItem(STORAGE_KEY);
    if (raw === null) return undefined;
    try {
      const parsed = JSON.parse(raw) as unknown;
      if (!isStoredCredential(parsed, this.nowEpochMillis())) {
        this.clear();
        return undefined;
      }
      return {
        credential: parsed.credential,
        expiresAtEpochMillis: parsed.expiresAtEpochMillis,
      };
    } catch {
      this.clear();
      return undefined;
    }
  }

  save(value: BrowserTrustedCredential): void {
    if (!isCredential(value.credential) || !isFutureEpoch(value.expiresAtEpochMillis, this.nowEpochMillis())) {
      throw new Error("Invalid trusted browser credential");
    }
    this.storage.setItem(STORAGE_KEY, JSON.stringify({
      version: FORMAT_VERSION,
      credential: value.credential,
      expiresAtEpochMillis: value.expiresAtEpochMillis,
    }));
  }

  clear(): void {
    this.storage.removeItem(STORAGE_KEY);
  }
}

function isStoredCredential(
  value: unknown,
  nowEpochMillis: number,
): value is {
  readonly version: 1;
  readonly credential: string;
  readonly expiresAtEpochMillis: number;
} {
  if (!hasExactKeys(value, ["credential", "expiresAtEpochMillis", "version"])) return false;
  return value.version === FORMAT_VERSION
    && typeof value.credential === "string"
    && isCredential(value.credential)
    && typeof value.expiresAtEpochMillis === "number"
    && isFutureEpoch(value.expiresAtEpochMillis, nowEpochMillis);
}

function isCredential(value: string): boolean {
  return value.length >= 1
    && value.length <= MAX_CREDENTIAL_LENGTH
    && CREDENTIAL_PATTERN.test(value);
}

function isFutureEpoch(value: number, nowEpochMillis: number): boolean {
  return Number.isSafeInteger(value) && value > nowEpochMillis;
}
