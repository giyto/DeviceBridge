export type BrowserProduct =
  | "Яндекс Браузер"
  | "Edge"
  | "Opera"
  | "Firefox"
  | "Chrome"
  | "Chromium"
  | "Safari"
  | "Браузер";

const productRules: ReadonlyArray<readonly [RegExp, BrowserProduct]> = [
  [/YaBrowser\//, "Яндекс Браузер"],
  [/(?:Edg|Edge)\//, "Edge"],
  [/(?:OPR|Opera)\//, "Opera"],
  [/Firefox\//, "Firefox"],
  [/Chrome\//, "Chrome"],
  [/Chromium\//, "Chromium"],
  [/Safari\//, "Safari"],
];

const maxBrowserLabelLength = 64;
const maxPlatformHintLength = 32;
const isoControlCharacters = /[\u0000-\u001f\u007f-\u009f]/;

export function detectBrowserProduct(userAgent: string): BrowserProduct {
  for (const [pattern, product] of productRules) {
    if (pattern.test(userAgent)) {
      return product;
    }
  }
  return "Браузер";
}

export function normalizePlatformHint(platformHint: string): string | undefined {
  if (isoControlCharacters.test(platformHint)) {
    return undefined;
  }
  const normalized = platformHint.trim().replace(/\s+/g, " ");
  if (!normalized) {
    return undefined;
  }
  if (/Android/i.test(normalized)) {
    return "Android";
  }
  if (/(?:iPhone|iPad|iPod)/i.test(normalized)) {
    return "iOS";
  }
  if (/(?:MacIntel|Macintosh|macOS)/i.test(normalized)) {
    return "macOS";
  }
  if (/(?:Win32|Win64|Windows)/i.test(normalized)) {
    return "Windows";
  }
  if (/Linux/i.test(normalized)) {
    return "Linux";
  }
  return normalized.slice(0, maxPlatformHintLength).trim() || undefined;
}

export function createBrowserLabel(
  userAgent: string,
  platformHint: string,
): string {
  const product = detectBrowserProduct(userAgent);
  const platform = normalizePlatformHint(platformHint);
  const label = platform === undefined ? product : `${product} • ${platform}`;
  return label.slice(0, maxBrowserLabelLength).trim();
}
