// @vitest-environment jsdom

import { describe, expect, it, vi } from "vitest";
import { NativeFileDownloader } from "../src/nativeFileDownloader";

describe("NativeFileDownloader", () => {
  it("hands a relative one-time URL to native download without navigating the session page", () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    const downloader = new NativeFileDownloader(document);

    downloader.start("/api/v1/files/file-1?grant=one-time", "../report.pdf");

    expect(click).toHaveBeenCalledOnce();
    const anchor = click.mock.instances[0] as HTMLAnchorElement;
    expect(anchor.getAttribute("href")).toBe("/api/v1/files/file-1?grant=one-time");
    expect(anchor.download).toBe("report.pdf");
    expect(anchor.referrerPolicy).toBe("no-referrer");
    expect(document.body.contains(anchor)).toBe(false);
    click.mockRestore();
  });

  it("rejects external, malformed and bearer-bearing paths", () => {
    const downloader = new NativeFileDownloader(document);
    expect(() => downloader.start("https://evil.example/file", "x.bin")).toThrow();
    expect(() => downloader.start("/api/v1/files/x?token=bearer-secret", "x.bin")).toThrow();
    expect(() => downloader.start("/api/v1/files/x/extra?grant=one-time", "x.bin")).toThrow();
    expect(() => downloader.start("/api/v1/files/x?grant=one&grant=two", "x.bin")).toThrow();
    expect(() => downloader.start("/other/path", "x.bin")).toThrow();
  });
});
