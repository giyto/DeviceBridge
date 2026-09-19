// @vitest-environment jsdom

import { beforeEach, describe, expect, it } from "vitest";
import { BrowserSecurityWarningPreferenceStore } from "../src/browserSecurityWarningPreferenceStore";
import { createSecurityWarningController } from "../src/securityWarningController";

describe("security warning presentation", () => {
  beforeEach(() => writeFixture());

  it("starts expanded, collapses across reload and expands on the same focused surface", () => {
    const storage = memoryStorage();
    let controller = createSecurityWarningController(
      document,
      new BrowserSecurityWarningPreferenceStore(storage),
    );
    controller.start();

    expect(warning().getAttribute("aria-expanded")).toBe("true");
    expect(warning().dataset.state).toBe("expanded");
    expect(detail().hidden).toBe(false);

    warning().focus();
    warning().click();
    expect(warning().getAttribute("aria-expanded")).toBe("false");
    expect(warning().dataset.state).toBe("collapsed");
    expect(detail().hidden).toBe(true);
    expect(document.activeElement).toBe(warning());
    expect(announcer().textContent).toContain("свёрнуто");

    controller.dispose();
    writeFixture();
    controller = createSecurityWarningController(
      document,
      new BrowserSecurityWarningPreferenceStore(storage),
    );
    controller.start();
    expect(warning().getAttribute("aria-expanded")).toBe("false");
    expect(detail().hidden).toBe(true);

    warning().focus();
    warning().click();
    expect(warning().getAttribute("aria-expanded")).toBe("true");
    expect(warning().dataset.state).toBe("expanded");
    expect(detail().hidden).toBe(false);
    expect(document.activeElement).toBe(warning());
    expect(announcer().textContent).toContain("раскрыто");
    controller.dispose();
  });

  it("uses ephemeral collapse when storage is unavailable and changes no security state", () => {
    const storage = throwingStorage();
    sessionStorage.setItem("devicebridge.session.v1", "session-secret");
    const trusted = memoryStorage();
    trusted.setItem("devicebridge.trusted-browser.v1", "trusted-record");
    const controller = createSecurityWarningController(
      document,
      new BrowserSecurityWarningPreferenceStore(storage),
    );
    controller.start();

    warning().click();
    expect(warning().getAttribute("aria-expanded")).toBe("false");
    expect(detail().hidden).toBe(true);
    expect(sessionStorage.getItem("devicebridge.session.v1")).toBe("session-secret");
    expect(trusted.getItem("devicebridge.trusted-browser.v1")).toBe("trusted-record");

    controller.dispose();
    writeFixture();
    const reloaded = createSecurityWarningController(
      document,
      new BrowserSecurityWarningPreferenceStore(storage),
    );
    reloaded.start();
    expect(warning().getAttribute("aria-expanded")).toBe("true");
    expect(detail().hidden).toBe(false);
    reloaded.dispose();
  });
});

function writeFixture(): void {
  document.body.innerHTML = [
    '<section data-role="connection-area" tabindex="-1">',
    '<button data-role="security-warning" type="button" aria-expanded="true"',
    ' aria-controls="security-warning-detail">',
    '<span id="security-warning-detail" data-role="security-warning-detail">',
    'Локальный HTTP не шифрует трафик.',
    '</span>',
    '</button>',
    '<p data-role="preference-announcer" aria-live="polite"></p>',
    '</section>',
  ].join("");
}

function warning(): HTMLButtonElement {
  return document.querySelector<HTMLButtonElement>('[data-role="security-warning"]')!;
}

function detail(): HTMLElement {
  return document.querySelector<HTMLElement>('[data-role="security-warning-detail"]')!;
}

function announcer(): HTMLElement {
  return document.querySelector<HTMLElement>('[data-role="preference-announcer"]')!;
}

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