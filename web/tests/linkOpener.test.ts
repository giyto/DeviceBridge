import { describe, expect, it, vi } from "vitest";
import { BrowserLinkOpener, canonicalHttpUrl } from "../src/linkOpener";

describe("canonicalHttpUrl", () => {
  it("accepts only server-classified absolute HTTP(S) links with a host", () => {
    expect(canonicalHttpUrl("https://example.com/path", "LINK"))
      .toBe("https://example.com/path");
    expect(canonicalHttpUrl("http://example.com", "LINK"))
      .toBe("http://example.com/");
    expect(canonicalHttpUrl("https://example.com", "TEXT")).toBeUndefined();
    expect(canonicalHttpUrl("javascript:alert(1)", "LINK")).toBeUndefined();
    expect(canonicalHttpUrl("data:text/plain,test", "LINK")).toBeUndefined();
    expect(canonicalHttpUrl("/relative", "LINK")).toBeUndefined();
  });

  it("opens a validated link in an isolated tab only after an explicit call", () => {
    const openWindow = vi.fn();
    const opener = new BrowserLinkOpener(openWindow);
    expect(openWindow).not.toHaveBeenCalled();

    expect(opener.open("https://example.com/path", "LINK")).toBe(true);
    expect(openWindow).toHaveBeenCalledWith(
      "https://example.com/path",
      "_blank",
      "noopener,noreferrer",
    );
    expect(opener.open("file:///tmp/private", "LINK")).toBe(false);
    expect(openWindow).toHaveBeenCalledOnce();
  });
});
