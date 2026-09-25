import { hasExactKeys, readRecord, removeRecord, writeRecord } from "./versionedRecord";

export const THEME_PREFERENCE_STORAGE_KEY = "devicebridge.theme.v1";
const FORMAT_VERSION = 1;
const DEFAULT_THEME: ThemePreference = "dark";

export type ThemePreference = "light" | "dark";

export class BrowserThemePreferenceStore {
  constructor(private readonly storage: Storage = globalThis.localStorage) {}

  read(): ThemePreference {
    const stored = readRecord(this.storage, THEME_PREFERENCE_STORAGE_KEY);
    if (stored === undefined) return DEFAULT_THEME;
    const parsed = stored.value;
    if (isLegacySystemPreference(parsed)) {
      this.save(DEFAULT_THEME);
      return DEFAULT_THEME;
    }
    if (!isStoredPreference(parsed)) {
      this.clear();
      return DEFAULT_THEME;
    }
    return parsed.preference;
  }

  save(preference: ThemePreference): boolean {
    if (!isThemePreference(preference)) return false;
    return writeRecord(this.storage, THEME_PREFERENCE_STORAGE_KEY, {
      version: FORMAT_VERSION,
      preference,
    });
  }

  clear(): boolean {
    return removeRecord(this.storage, THEME_PREFERENCE_STORAGE_KEY);
  }
}

function isStoredPreference(
  value: unknown,
): value is { readonly version: 1; readonly preference: ThemePreference } {
  return hasExactKeys(value, ["preference", "version"])
    && value.version === FORMAT_VERSION
    && isThemePreference(value.preference);
}

function isLegacySystemPreference(
  value: unknown,
): value is { readonly version: 1; readonly preference: "system" } {
  return hasExactKeys(value, ["preference", "version"])
    && value.version === FORMAT_VERSION
    && value.preference === "system";
}

function isThemePreference(value: unknown): value is ThemePreference {
  return value === "light" || value === "dark";
}
