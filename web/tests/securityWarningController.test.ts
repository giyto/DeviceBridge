// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CertificateTrust } from "../src/certificateTrustProbe";
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

describe("secure connection note", () => {
  beforeEach(() => writeFixture());

  it("replaces the plain HTTP warning over HTTPS and keeps no collapse control", async () => {
    const storage = memoryStorage();
    const controller = createSecurityWarningController(
      document,
      new BrowserSecurityWarningPreferenceStore(storage),
      secureConnection("trusted"),
    );
    controller.start();

    expect(warning().hidden).toBe(true);
    await vi.waitFor(() => expect(secureNote().hidden).toBe(false));
    expect(untrustedNote().hidden).toBe(true);
    expect(secureNote().textContent).toContain("Соединение с телефоном зашифровано");
    expect(secureNote().textContent).toContain("в закладки");
    warning().click();
    expect(storage.length).toBe(0);
    controller.dispose();
  });

  it("warns and links to the setup page when the browser clicked through the certificate warning", async () => {
    const controller = createSecurityWarningController(
      document,
      new BrowserSecurityWarningPreferenceStore(memoryStorage()),
      secureConnection("untrusted"),
    );
    controller.start();

    await vi.waitFor(() => expect(untrustedNote().hidden).toBe(false));
    expect(untrustedNote().textContent).toContain("Сертификат телефона не установлен");
    expect(installLink().href).toBe(SETUP_URL);
    expect(secureNote().hidden).toBe(true);
    expect(warning().hidden).toBe(true);
    controller.dispose();
  });

  it("shows no false alarm when the browser does not tell", async () => {
    for (const connection of [
      secureConnection("unknown"),
      { probeTrust: () => Promise.reject(new Error("boom")), setupUrl: SETUP_URL },
    ]) {
      writeFixture();
      createSecurityWarningController(
        document,
        new BrowserSecurityWarningPreferenceStore(memoryStorage()),
        connection,
      ).start();
      await vi.waitFor(() => expect(secureNote().hidden).toBe(false));
      expect(untrustedNote().hidden).toBe(true);
    }
  });

  it("keeps the note hidden over plain HTTP", () => {
    const controller = createSecurityWarningController(
      document,
      new BrowserSecurityWarningPreferenceStore(memoryStorage()),
    );
    controller.start();

    expect(warning().hidden).toBe(false);
    expect(secureNote().hidden).toBe(true);
    expect(untrustedNote().hidden).toBe(true);
    controller.dispose();
  });
});

const SETUP_URL = "http://192.168.1.24:8787/?untrusted=1";

function secureConnection(trust: CertificateTrust) {
  return { probeTrust: async () => trust, setupUrl: SETUP_URL };
}

function untrustedNote(): HTMLElement {
  return document.querySelector<HTMLElement>('[data-role="untrusted-certificate-note"]')!;
}

function installLink(): HTMLAnchorElement {
  return document.querySelector<HTMLAnchorElement>('[data-role="install-certificate-link"]')!;
}

function secureNote(): HTMLElement {
  return document.querySelector<HTMLElement>('[data-role="secure-connection-note"]')!;
}

function writeFixture(): void {
  document.body.innerHTML = [
    '<section data-role="connection-area" tabindex="-1">',
    '<button data-role="security-warning" type="button" aria-expanded="true"',
    ' aria-controls="security-warning-detail">',
    '<span id="security-warning-detail" data-role="security-warning-detail">',
    'Локальный HTTP не шифрует трафик.',
    '</span>',
    '</button>',
    '<p data-role="secure-connection-note" hidden>Соединение с телефоном зашифровано.',
    ' Сохраните эту страницу в закладки.</p>',
    '<div data-role="untrusted-certificate-note" hidden>Сертификат телефона не установлен',
    '<a data-role="install-certificate-link" href="/">Установить сертификат</a></div>',
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