import { describe, expect, it } from "vitest";
import { BrowserTrustedCredentialStore } from "../src/browserTrustedCredentialStore";

describe("BrowserTrustedCredentialStore", () => {
  it("saves and reads a versioned credential independently from session storage", () => {
    const local = memoryStorage();
    const store = new BrowserTrustedCredentialStore(local, () => 1_000);

    store.save({ credential: "trusted_ABC-123", expiresAtEpochMillis: 2_000 });

    expect(store.read()).toEqual({
      credential: "trusted_ABC-123",
      expiresAtEpochMillis: 2_000,
    });
    expect(local.getItem("devicebridge.trusted-browser.v1")).toContain('"version":1');
  });

  it("clears expired, malformed and unsupported records", () => {
    for (const invalid of [
      "not-json",
      JSON.stringify({ version: 2, credential: "token", expiresAtEpochMillis: 2_000 }),
      JSON.stringify({ version: 1, credential: "bad value", expiresAtEpochMillis: 2_000 }),
      JSON.stringify({ version: 1, credential: "token", expiresAtEpochMillis: 1_000 }),
    ]) {
      const local = memoryStorage();
      local.setItem("devicebridge.trusted-browser.v1", invalid);
      const store = new BrowserTrustedCredentialStore(local, () => 1_000);

      expect(store.read()).toBeUndefined();
      expect(local.length).toBe(0);
    }
  });

  it("rejects invalid writes and clears explicitly", () => {
    const local = memoryStorage();
    const store = new BrowserTrustedCredentialStore(local, () => 1_000);

    expect(() => store.save({ credential: " ", expiresAtEpochMillis: 2_000 })).toThrow();
    store.save({ credential: "valid-token", expiresAtEpochMillis: 2_000 });
    store.clear();
    expect(store.read()).toBeUndefined();
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
