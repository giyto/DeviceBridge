// @vitest-environment jsdom

import { describe, expect, it, vi } from "vitest";
import {
  BrowserThemePreferenceStore,
  THEME_PREFERENCE_STORAGE_KEY,
} from "../src/browserThemePreferenceStore";
import { createDocumentThemeApplication, ThemeController } from "../src/themeController";

describe("ThemeController", () => {
  it("starts in dark and applies only explicit light or dark choices", () => {
    const storage = memoryStorage();
    const store = new BrowserThemePreferenceStore(storage);
    const apply = vi.fn();
    const controller = new ThemeController(store, apply, window);

    controller.start();
    expect(apply).toHaveBeenLastCalledWith("dark");

    controller.setPreference("light");
    expect(apply).toHaveBeenLastCalledWith("light");

    controller.setPreference("dark");
    expect(apply).toHaveBeenLastCalledWith("dark");
    controller.dispose();
  });

  it("applies effective theme and color-scheme to the existing document", () => {
    document.head.innerHTML = '<meta name="color-scheme" content="light dark">';
    const apply = createDocumentThemeApplication(document);

    apply("dark");

    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(document.documentElement.dataset.themePreference).toBe("dark");
    expect(document.documentElement.style.colorScheme).toBe("dark");
    expect(document.querySelector('meta[name="color-scheme"]')?.getAttribute("content"))
      .toBe("dark");
  });

  it("synchronizes another tab and preserves session and draft records", () => {
    const storage = memoryStorage();
    storage.setItem("devicebridge.session.v1", "session-secret");
    storage.setItem("devicebridge.text-draft.v1", "draft");
    const store = new BrowserThemePreferenceStore(storage);
    const apply = vi.fn();
    const controller = new ThemeController(store, apply, window);

    controller.start();
    store.save("light");
    window.dispatchEvent(new StorageEvent("storage", {
      key: THEME_PREFERENCE_STORAGE_KEY,
    }));

    expect(apply).toHaveBeenLastCalledWith("light");
    expect(storage.getItem("devicebridge.session.v1")).toBe("session-secret");
    expect(storage.getItem("devicebridge.text-draft.v1")).toBe("draft");
    controller.dispose();
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
