import { hasExactKeys, readRecord, removeRecord, writeRecord } from "./versionedRecord";

export const SECURITY_WARNING_STORAGE_KEY = "devicebridge.security-warning.v1";
const FORMAT_VERSION = 1;

export class BrowserSecurityWarningPreferenceStore {
  constructor(private readonly storage: Storage = globalThis.localStorage) {}

  isDismissed(): boolean {
    const stored = readRecord(this.storage, SECURITY_WARNING_STORAGE_KEY);
    if (stored === undefined) return false;
    if (!isDismissedRecord(stored.value)) {
      this.restore();
      return false;
    }
    return true;
  }

  dismiss(): boolean {
    return writeRecord(this.storage, SECURITY_WARNING_STORAGE_KEY, {
      version: FORMAT_VERSION,
      dismissed: true,
    });
  }

  restore(): boolean {
    return removeRecord(this.storage, SECURITY_WARNING_STORAGE_KEY);
  }
}

function isDismissedRecord(
  value: unknown,
): value is { readonly version: 1; readonly dismissed: true } {
  return hasExactKeys(value, ["dismissed", "version"])
    && value.version === FORMAT_VERSION
    && value.dismissed === true;
}
