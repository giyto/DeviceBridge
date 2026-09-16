import { describe, expect, it } from "vitest";
import { BrowserSessionTokenStore } from "../src/sessionTokenStore";

describe("BrowserSessionTokenStore", () => {
  it("stores a token only in the supplied tab session storage", () => {
    const session = new MemoryStorage();
    const local = new MemoryStorage();
    const store = new BrowserSessionTokenStore(session);

    store.save("tab-token");

    expect(store.read()).toBe("tab-token");
    expect(session.length).toBe(1);
    expect(local.length).toBe(0);
    store.clear();
    expect(store.read()).toBeUndefined();
  });

  it("rejects blank or malformed persisted values", () => {
    const session = new MemoryStorage();
    const store = new BrowserSessionTokenStore(session);
    session.setItem("devicebridge.session.v1", "\nsecret");

    expect(store.read()).toBeUndefined();
    expect(session.length).toBe(0);
    expect(() => store.save(" ")).toThrow();
  });
});

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();
  get length(): number { return this.values.size; }
  clear(): void { this.values.clear(); }
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  key(index: number): string | null { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string): void { this.values.delete(key); }
  setItem(key: string, value: string): void { this.values.set(key, value); }
}
