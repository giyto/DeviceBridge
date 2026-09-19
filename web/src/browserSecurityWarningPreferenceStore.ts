export const SECURITY_WARNING_STORAGE_KEY = "devicebridge.security-warning.v1";
const FORMAT_VERSION = 1;

export class BrowserSecurityWarningPreferenceStore {
  constructor(private readonly storage: Storage = globalThis.localStorage) {}

  isDismissed(): boolean {
    let raw: string | null;
    try {
      raw = this.storage.getItem(SECURITY_WARNING_STORAGE_KEY);
    } catch {
      return false;
    }
    if (raw === null) return false;

    try {
      const parsed = JSON.parse(raw) as unknown;
      if (!isDismissedRecord(parsed)) {
        this.restore();
        return false;
      }
      return true;
    } catch {
      this.restore();
      return false;
    }
  }

  dismiss(): boolean {
    try {
      this.storage.setItem(
        SECURITY_WARNING_STORAGE_KEY,
        JSON.stringify({
          version: FORMAT_VERSION,
          dismissed: true,
        }),
      );
      return true;
    } catch {
      return false;
    }
  }

  restore(): boolean {
    try {
      this.storage.removeItem(SECURITY_WARNING_STORAGE_KEY);
      return true;
    } catch {
      return false;
    }
  }
}

function isDismissedRecord(
  value: unknown,
): value is { readonly version: 1; readonly dismissed: true } {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const record = value as Record<string, unknown>;
  return Object.keys(record).sort().join(",") === "dismissed,version"
    && record.version === FORMAT_VERSION
    && record.dismissed === true;
}
