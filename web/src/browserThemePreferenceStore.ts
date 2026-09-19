export const THEME_PREFERENCE_STORAGE_KEY = "devicebridge.theme.v1";
const FORMAT_VERSION = 1;
const DEFAULT_THEME: ThemePreference = "dark";

export type ThemePreference = "light" | "dark";

export class BrowserThemePreferenceStore {
  constructor(private readonly storage: Storage = globalThis.localStorage) {}

  read(): ThemePreference {
    let raw: string | null;
    try {
      raw = this.storage.getItem(THEME_PREFERENCE_STORAGE_KEY);
    } catch {
      return DEFAULT_THEME;
    }
    if (raw === null) return DEFAULT_THEME;

    try {
      const parsed = JSON.parse(raw) as unknown;
      if (isLegacySystemPreference(parsed)) {
        this.save(DEFAULT_THEME);
        return DEFAULT_THEME;
      }
      if (!isStoredPreference(parsed)) {
        this.clear();
        return DEFAULT_THEME;
      }
      return parsed.preference;
    } catch {
      this.clear();
      return DEFAULT_THEME;
    }
  }

  save(preference: ThemePreference): boolean {
    if (!isThemePreference(preference)) return false;
    try {
      this.storage.setItem(
        THEME_PREFERENCE_STORAGE_KEY,
        JSON.stringify({
          version: FORMAT_VERSION,
          preference,
        }),
      );
      return true;
    } catch {
      return false;
    }
  }

  clear(): boolean {
    try {
      this.storage.removeItem(THEME_PREFERENCE_STORAGE_KEY);
      return true;
    } catch {
      return false;
    }
  }
}

function hasExactStoredShape(value: unknown): value is Record<string, unknown> {
  return typeof value === "object"
    && value !== null
    && !Array.isArray(value)
    && Object.keys(value).sort().join(",") === "preference,version";
}

function isStoredPreference(
  value: unknown,
): value is { readonly version: 1; readonly preference: ThemePreference } {
  return hasExactStoredShape(value)
    && value.version === FORMAT_VERSION
    && isThemePreference(value.preference);
}

function isLegacySystemPreference(
  value: unknown,
): value is { readonly version: 1; readonly preference: "system" } {
  return hasExactStoredShape(value)
    && value.version === FORMAT_VERSION
    && value.preference === "system";
}

function isThemePreference(value: unknown): value is ThemePreference {
  return value === "light" || value === "dark";
}
