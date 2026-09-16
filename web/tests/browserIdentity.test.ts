import { describe, expect, it } from "vitest";
import {
  createBrowserLabel,
  detectBrowserProduct,
  normalizePlatformHint,
} from "../src/browserIdentity";

describe("detectBrowserProduct", () => {
  it.each([
    [
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 Chrome/128.0.0.0 YaBrowser/24.10.0.0 Safari/537.36",
      "Яндекс Браузер",
    ],
    [
      "Mozilla/5.0 AppleWebKit/537.36 Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0",
      "Edge",
    ],
    [
      "Mozilla/5.0 AppleWebKit/537.36 Chrome/128.0.0.0 Safari/537.36 OPR/113.0.0.0",
      "Opera",
    ],
    ["Mozilla/5.0 Gecko/20100101 Firefox/130.0", "Firefox"],
    [
      "Mozilla/5.0 AppleWebKit/537.36 Chrome/128.0.0.0 Safari/537.36",
      "Chrome",
    ],
    ["Mozilla/5.0 AppleWebKit/537.36 Chromium/128.0.0.0 Safari/537.36", "Chromium"],
    ["Mozilla/5.0 Version/17.6 Safari/605.1.15", "Safari"],
    ["custom-client/1.0", "Браузер"],
    ["", "Браузер"],
  ])("classifies %s", (userAgent, expected) => {
    expect(detectBrowserProduct(userAgent)).toBe(expected);
  });

  it("uses product-specific priority before shared Chromium tokens", () => {
    const overloaded =
      "Safari/537.36 Chrome/128.0.0.0 Chromium/128.0.0.0 " +
      "Firefox/130.0 OPR/113.0 Edg/128.0 YaBrowser/24.10";

    expect(detectBrowserProduct(overloaded)).toBe("Яндекс Браузер");
  });
});

describe("createBrowserLabel", () => {
  const yandexUserAgent =
    "Mozilla/5.0 AppleWebKit/537.36 Chrome/128.0.0.0 " +
    "YaBrowser/24.10.0.0 Safari/537.36";

  it("keeps the exact Yandex product label when platform is unavailable", () => {
    expect(createBrowserLabel(yandexUserAgent, "   ")).toBe("Яндекс Браузер");
  });

  it.each([
    ["Win32", "Windows"],
    ["MacIntel", "macOS"],
    ["Linux x86_64", "Linux"],
    ["Linux armv8l; Android", "Android"],
    ["iPhone", "iOS"],
  ])("normalizes platform %s", (platform, expected) => {
    expect(normalizePlatformHint(platform)).toBe(expected);
  });

  it("uses a bounded safe platform without exposing raw User-Agent", () => {
    const rawPlatform = "  Workstation " + "x".repeat(200) + "  ";
    const label = createBrowserLabel(yandexUserAgent, rawPlatform);

    expect(label.startsWith("Яндекс Браузер • Workstation")).toBe(true);
    expect(label.length).toBeLessThanOrEqual(64);
    expect(label).not.toContain("YaBrowser/24.10");
    expect(label).not.toContain("Chrome/128");
  });

  it("drops empty and control-like platform hints", () => {
    expect(createBrowserLabel("", "")).toBe("Браузер");
    expect(createBrowserLabel(yandexUserAgent, "Windows\nInjected"))
      .toBe("Яндекс Браузер");
    expect(createBrowserLabel("", "Linux")).toBe("Браузер • Linux");
  });
});
