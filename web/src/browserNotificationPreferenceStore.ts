export const NOTIFICATION_PREFERENCE_STORAGE_KEY = "devicebridge.notifications.v1";
const FORMAT_VERSION = 1;

export interface NotificationPreference {
  readonly enabled: boolean;
  readonly hideContent: boolean;
}

export const DEFAULT_NOTIFICATION_PREFERENCE: NotificationPreference = {
  enabled: false,
  hideContent: false,
};

/** Whether this browser shows DeviceBridge notifications and how much they reveal. */
export class BrowserNotificationPreferenceStore {
  constructor(private readonly storage: Storage = globalThis.localStorage) {}

  read(): NotificationPreference {
    let raw: string | null;
    try {
      raw = this.storage.getItem(NOTIFICATION_PREFERENCE_STORAGE_KEY);
    } catch {
      return DEFAULT_NOTIFICATION_PREFERENCE;
    }
    if (raw === null) return DEFAULT_NOTIFICATION_PREFERENCE;

    try {
      const parsed = JSON.parse(raw) as unknown;
      if (isPreferenceRecord(parsed)) {
        return { enabled: parsed.enabled, hideContent: parsed.hideContent };
      }
    } catch {
      // An unreadable record is dropped below.
    }
    this.clear();
    return DEFAULT_NOTIFICATION_PREFERENCE;
  }

  save(preference: NotificationPreference): boolean {
    try {
      this.storage.setItem(
        NOTIFICATION_PREFERENCE_STORAGE_KEY,
        JSON.stringify({
          version: FORMAT_VERSION,
          enabled: preference.enabled,
          hideContent: preference.hideContent,
        }),
      );
      return true;
    } catch {
      return false;
    }
  }

  clear(): boolean {
    try {
      this.storage.removeItem(NOTIFICATION_PREFERENCE_STORAGE_KEY);
      return true;
    } catch {
      return false;
    }
  }
}

function isPreferenceRecord(
  value: unknown,
): value is { readonly version: 1; readonly enabled: boolean; readonly hideContent: boolean } {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const record = value as Record<string, unknown>;
  return Object.keys(record).sort().join(",") === "enabled,hideContent,version"
    && record.version === FORMAT_VERSION
    && typeof record.enabled === "boolean"
    && typeof record.hideContent === "boolean";
}
