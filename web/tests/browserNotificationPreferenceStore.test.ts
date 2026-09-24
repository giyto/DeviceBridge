import { describe, expect, it } from "vitest";
import {
  BrowserNotificationPreferenceStore,
  DEFAULT_NOTIFICATION_PREFERENCE,
  NOTIFICATION_PREFERENCE_STORAGE_KEY,
} from "../src/browserNotificationPreferenceStore";

describe("BrowserNotificationPreferenceStore", () => {
  it("is off and shows content by default, and keeps a saved choice", () => {
    const storage = memoryStorage();
    const store = new BrowserNotificationPreferenceStore(storage);

    expect(store.read()).toEqual({ enabled: false, hideContent: false });
    expect(store.save({ enabled: true, hideContent: true })).toBe(true);
    expect(store.read()).toEqual({ enabled: true, hideContent: true });
    expect(storage.getItem(NOTIFICATION_PREFERENCE_STORAGE_KEY)).toContain('"version":1');

    expect(store.clear()).toBe(true);
    expect(store.read()).toEqual(DEFAULT_NOTIFICATION_PREFERENCE);
  });

  it("drops invalid records", () => {
    for (const raw of [
      "not-json",
      JSON.stringify({ version: 2, enabled: true, hideContent: false }),
      JSON.stringify({ version: 1, enabled: "yes", hideContent: false }),
      JSON.stringify({ version: 1, enabled: true }),
      JSON.stringify({ version: 1, enabled: true, hideContent: false, extra: 1 }),
    ]) {
      const storage = memoryStorage();
      storage.setItem(NOTIFICATION_PREFERENCE_STORAGE_KEY, raw);

      expect(new BrowserNotificationPreferenceStore(storage).read()).toEqual(DEFAULT_NOTIFICATION_PREFERENCE);
      expect(storage.getItem(NOTIFICATION_PREFERENCE_STORAGE_KEY)).toBeNull();
    }
  });

  it("falls back to the default when browser storage is unavailable", () => {
    const store = new BrowserNotificationPreferenceStore(throwingStorage());

    expect(store.read()).toEqual(DEFAULT_NOTIFICATION_PREFERENCE);
    expect(store.save({ enabled: true, hideContent: false })).toBe(false);
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
