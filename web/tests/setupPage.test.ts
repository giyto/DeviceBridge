// @vitest-environment jsdom

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createSetupPage } from "../src/setup/setupPage";
import { detectSetupPlatform } from "../src/setup/setupPlatform";
import { createTrustProbe, httpsOriginOf, TLS_PROBE_PATH } from "../src/setup/trustProbe";

const setupHtml = readFileSync(resolve(process.cwd(), "setup.html"), "utf8");

describe("setup platform", () => {
  it("prefers Firefox, then the operating system", () => {
    expect(detectSetupPlatform("Mozilla/5.0 (Windows NT 10.0; rv:130.0) Gecko/20100101 Firefox/130.0"))
      .toBe("firefox");
    expect(detectSetupPlatform("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0 Safari/537.36 Edg/140.0"))
      .toBe("windows");
    expect(detectSetupPlatform("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) Version/17.5 Safari/605.1.15"))
      .toBe("macos");
    expect(detectSetupPlatform("Mozilla/5.0 (X11; Linux x86_64) Chrome/140.0 Safari/537.36"))
      .toBe("linux");
    expect(detectSetupPlatform("")).toBe("windows");
  });
});

describe("trust probe", () => {
  it("asks the same host over HTTPS without credentials or CORS", async () => {
    const fetchFn = vi.fn(async () => ({}));
    const probe = createTrustProbe(
      fetchFn,
      httpsOriginOf({ href: "http://192.168.1.24:8787/devicebridge-ca.crt?x=1" }),
    );

    await expect(probe()).resolves.toBe(true);
    expect(fetchFn).toHaveBeenCalledWith(
      "https://192.168.1.24:8787" + TLS_PROBE_PATH,
      expect.objectContaining({ mode: "no-cors", credentials: "omit", cache: "no-store" }),
    );
  });

  it("treats a refused certificate and a silent server as untrusted", async () => {
    const refused = createTrustProbe(async () => {
      throw new TypeError("Failed to fetch");
    }, "https://192.168.1.24:8787");
    await expect(refused()).resolves.toBe(false);

    const silent = createTrustProbe(
      (_input, init) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
        }),
      "https://192.168.1.24:8787",
      20,
    );
    await expect(silent()).resolves.toBe(false);
  });
});

describe("setup page", () => {
  beforeEach(() => {
    document.documentElement.innerHTML = setupHtml.replace(/<!doctype html>/i, "");
  });

  it("checks trust on start and offers another check until the root is installed", async () => {
    const probe = vi.fn(async () => false);
    const openSecurePage = vi.fn();
    const page = createSetupPage(document, { platform: "windows", probe, openSecurePage });

    page.start();
    expect(status().dataset.state).toBe("checking");
    expect(checkAgain().disabled).toBe(true);
    await vi.waitFor(() => expect(status().dataset.state).toBe("untrusted"));
    expect(title().textContent).toContain("ещё не установлен");
    expect(checkAgain().disabled).toBe(false);

    probe.mockResolvedValue(true);
    checkAgain().click();
    await vi.waitFor(() => expect(openSecurePage).toHaveBeenCalledTimes(1));
    expect(status().dataset.state).toBe("trusted");
    expect(checkAgain().hidden).toBe(true);
  });

  it("does not run two checks at once", async () => {
    let finish: (trusted: boolean) => void = () => undefined;
    const probe = vi.fn(() => new Promise<boolean>((done) => { finish = done; }));
    const page = createSetupPage(document, { platform: "windows", probe, openSecurePage: vi.fn() });

    const first = page.check();
    const second = page.check();
    finish(false);

    await expect(first).resolves.toBe(false);
    await expect(second).resolves.toBe(false);
    expect(probe).toHaveBeenCalledTimes(1);
  });

  it("after a remembered click-through it neither checks nor leaves, and asks for a restart", () => {
    const probe = vi.fn(async () => true);
    const openSecurePage = vi.fn();

    createSetupPage(document, { platform: "windows", probe, openSecurePage, rememberedBypass: true }).start();

    expect(status().dataset.state).toBe("bypassed");
    expect(title().textContent).toContain("без сертификата");
    expect(document.querySelector('[data-role="trust-detail"]')!.textContent).toContain("закройте браузер");
    expect(checkAgain().hidden).toBe(true);
    expect(probe).not.toHaveBeenCalled();
    expect(openSecurePage).not.toHaveBeenCalled();
    expect(guide("windows").hidden).toBe(false);
  });

  it("shows the guide for the detected platform and switches on tab click", () => {
    createSetupPage(document, {
      platform: "firefox",
      probe: async () => false,
      openSecurePage: vi.fn(),
    }).start();

    expect(guide("firefox").hidden).toBe(false);
    expect(guide("windows").hidden).toBe(true);
    expect(tab("firefox").getAttribute("aria-selected")).toBe("true");

    tab("macos").click();
    expect(guide("macos").hidden).toBe(false);
    expect(guide("firefox").hidden).toBe(true);
    expect(tab("macos").getAttribute("aria-selected")).toBe("true");
  });

  it("links the certificate download and asks to compare the fingerprint with the phone", () => {
    const download = document.querySelector<HTMLAnchorElement>('[data-role="download"]')!;
    const fingerprint = document.querySelector('[data-role="fingerprint-warning"]')!.textContent!;

    expect(download.getAttribute("href")).toBe("/devicebridge-ca.crt");
    expect(fingerprint).toContain("сверьте");
    expect(fingerprint).toContain("первые и последние");
    expect(fingerprint).toContain("Поделиться сертификатом");
    expect(fingerprint).toContain("чужой Wi-Fi");
  });

  it("warns against clicking through the browser warning", () => {
    expect(document.querySelector('[data-role="proceed-warning"]')!.textContent)
      .toContain("Всё равно перейти");
  });
});

function status(): HTMLElement {
  return document.querySelector<HTMLElement>('[data-role="trust-status"]')!;
}

function title(): HTMLElement {
  return document.querySelector<HTMLElement>('[data-role="trust-title"]')!;
}

function checkAgain(): HTMLButtonElement {
  return document.querySelector<HTMLButtonElement>('[data-action="check-again"]')!;
}

function guide(platform: string): HTMLElement {
  return document.querySelector<HTMLElement>(`[data-guide="${platform}"]`)!;
}

function tab(platform: string): HTMLButtonElement {
  return document.querySelector<HTMLButtonElement>(`[data-platform="${platform}"]`)!;
}
