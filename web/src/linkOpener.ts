import type { TextContentKind } from "./textApiClient";

export interface LinkOpener {
  open(content: string, contentKind: TextContentKind): boolean;
}

type OpenWindow = (
  url?: string | URL,
  target?: string,
  features?: string,
) => unknown;

export class BrowserLinkOpener implements LinkOpener {
  constructor(
    private readonly openWindow: OpenWindow = globalThis.open.bind(globalThis),
  ) {}

  open(content: string, contentKind: TextContentKind): boolean {
    const url = canonicalHttpUrl(content, contentKind);
    if (url === undefined) return false;
    this.openWindow(url, "_blank", "noopener,noreferrer");
    return true;
  }
}

export function canonicalHttpUrl(
  content: string,
  contentKind: TextContentKind,
): string | undefined {
  if (contentKind !== "LINK" || content !== content.trim()) return undefined;
  try {
    const url = new URL(content);
    if (
      (url.protocol !== "http:" && url.protocol !== "https:") ||
      url.hostname.length === 0
    ) return undefined;
    return url.href;
  } catch {
    return undefined;
  }
}
