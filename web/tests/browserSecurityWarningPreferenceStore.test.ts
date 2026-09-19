import { describe, expect, it } from "vitest";
import {
  BrowserSecurityWarningPreferenceStore,
  SECURITY_WARNING_STORAGE_KEY,
} from "../src/browserSecurityWarningPreferenceStore";

describe("BrowserSecurityWarningPreferenceStore", () => {
  it("shows by default and persists only an explicit dismissal", () => {
    const storage = memoryStorage();
    const store = new BrowserSecurityWarningPreferenceStore(storage);

    expect(store.isDismissed()).toBe(false);
    expect(store.dismiss()).toBe(true);
    expect(store.isDismissed()).toBe(true);
    expect(storage.getItem(SECURITY_WARNING_STORAGE_KEY)).toContain('"version":1');

    expect(store.restore()).toBe(true);
    expect(store.isDismissed()).toBe(false);
  });

  it("clears invalid records and keeps the warning visible", () => {
    for (const raw of [
      "not-json",
      JSON.stringify({ version: 2, dismissed: true }),
      JSON.stringify({ version: 1, dismissed: false }),
      JSON.stringify({ version: 1, dismissed: true, network: "home" }),
    ]) {
      const storage = memoryStorage();
      storage.setItem(SECURITY_WARNING_STORAGE_KEY, raw);
      const store = new BrowserSecurityWarningPreferenceStore(storage);

      expect(store.isDismissed()).toBe(false);
      expect(storage.getItem(SECURITY_WARNING_STORAGE_KEY)).toBeNull();
    }
  });

  it("fails closed when browser storage is unavailable", () => {
    const store = new BrowserSecurityWarningPreferenceStore(throwingStorage());

    expect(store.isDismissed()).toBe(false);
    expect(store.dismiss()).toBe(false);
    expect(store.restore()).toBe(false);
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
