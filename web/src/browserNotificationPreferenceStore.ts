import { hasExactKeys, readRecord, removeRecord, writeRecord } from "./versionedRecord";

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
    const stored = readRecord(this.storage, NOTIFICATION_PREFERENCE_STORAGE_KEY);
    if (stored === undefined) return DEFAULT_NOTIFICATION_PREFERENCE;
    const parsed = stored.value;
    if (isPreferenceRecord(parsed)) {
      return { enabled: parsed.enabled, hideContent: parsed.hideContent };
    }
    this.clear();
    return DEFAULT_NOTIFICATION_PREFERENCE;
  }

  save(preference: NotificationPreference): boolean {
    return writeRecord(this.storage, NOTIFICATION_PREFERENCE_STORAGE_KEY, {
      version: FORMAT_VERSION,
      enabled: preference.enabled,
      hideContent: preference.hideContent,
    });
  }

  clear(): boolean {
    return removeRecord(this.storage, NOTIFICATION_PREFERENCE_STORAGE_KEY);
  }
}

function isPreferenceRecord(
  value: unknown,
): value is { readonly version: 1; readonly enabled: boolean; readonly hideContent: boolean } {
  return hasExactKeys(value, ["enabled", "hideContent", "version"])
    && value.version === FORMAT_VERSION
    && typeof value.enabled === "boolean"
    && typeof value.hideContent === "boolean";
}
