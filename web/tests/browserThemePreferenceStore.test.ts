import { describe, expect, it } from "vitest";
import {
  BrowserThemePreferenceStore,
  THEME_PREFERENCE_STORAGE_KEY,
} from "../src/browserThemePreferenceStore";

describe("BrowserThemePreferenceStore", () => {
  it("defaults to dark and round-trips only explicit light and dark preferences", () => {
    const storage = memoryStorage();
    const store = new BrowserThemePreferenceStore(storage);

    expect(store.read()).toBe("dark");
    expect(store.save("light")).toBe(true);
    expect(store.read()).toBe("light");
    expect(store.save("dark")).toBe(true);
    expect(store.read()).toBe("dark");
    expect(storage.getItem(THEME_PREFERENCE_STORAGE_KEY)).toContain('"version":1');

    expect(store.clear()).toBe(true);
    expect(store.read()).toBe("dark");
  });

  it("migrates the legacy system preference to dark without touching unrelated data", () => {
    const storage = memoryStorage();
    storage.setItem(
      THEME_PREFERENCE_STORAGE_KEY,
      JSON.stringify({ version: 1, preference: "system" }),
    );
    storage.setItem("devicebridge.session.v1", "session-secret");

    const store = new BrowserThemePreferenceStore(storage);

    expect(store.read()).toBe("dark");
    expect(storage.getItem(THEME_PREFERENCE_STORAGE_KEY)).toBe(
      JSON.stringify({ version: 1, preference: "dark" }),
    );
    expect(storage.getItem("devicebridge.session.v1")).toBe("session-secret");
  });

  it("clears malformed, unsupported and invalid values to the dark fallback", () => {
    for (const raw of [
      "not-json",
      JSON.stringify({ version: 2, preference: "dark" }),
      JSON.stringify({ version: 1, preference: "sepia" }),
      JSON.stringify({ version: 1, preference: "light", extra: true }),
    ]) {
      const storage = memoryStorage();
      storage.setItem(THEME_PREFERENCE_STORAGE_KEY, raw);
      const store = new BrowserThemePreferenceStore(storage);

      expect(store.read()).toBe("dark");
      expect(storage.getItem(THEME_PREFERENCE_STORAGE_KEY)).toBeNull();
    }
  });

  it("falls back safely when browser storage is unavailable", () => {
    const store = new BrowserThemePreferenceStore(throwingStorage());

    expect(store.read()).toBe("dark");
    expect(store.save("dark")).toBe(false);
    expect(store.clear()).toBe(false);
  });
});

function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() { return values.size; },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => [...values.keys()][index] ?? null,
    removeItem: (key) => { values.delete(key); },
    setItem: (key, value) => { values.set(key, value); },
  };
}

function throwingStorage(): Storage {
  const fail = (): never => { throw new DOMException("blocked", "SecurityError"); };
  return {
    get length(): number { return fail(); },
    clear: fail,
    getItem: fail,
    key: fail,
    removeItem: fail,
    setItem: fail,
  };
}
