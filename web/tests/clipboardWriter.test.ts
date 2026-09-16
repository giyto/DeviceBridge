import { describe, expect, it, vi } from "vitest";
import { BrowserClipboardWriter } from "../src/clipboardWriter";

describe("BrowserClipboardWriter", () => {
  it("writes only after the explicit call in a secure context", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    const writer = new BrowserClipboardWriter({
      isSecureContext: true,
      clipboard: { writeText },
    });

    await expect(writer.write("явно скопировать")).resolves.toBe(true);
    expect(writeText).toHaveBeenCalledWith("явно скопировать");
  });

  it("uses manual fallback in an insecure context without touching Clipboard API", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    const writer = new BrowserClipboardWriter({
      isSecureContext: false,
      clipboard: { writeText },
    });

    await expect(writer.write("LAN HTTP")).resolves.toBe(false);
    expect(writeText).not.toHaveBeenCalled();
  });

  it("uses manual fallback when clipboard permission is rejected", async () => {
    const writeText = vi.fn().mockRejectedValue(new DOMException("denied", "NotAllowedError"));
    const writer = new BrowserClipboardWriter({
      isSecureContext: true,
      clipboard: { writeText },
    });

    await expect(writer.write("не скопировано")).resolves.toBe(false);
    expect(writeText).toHaveBeenCalledOnce();
  });
});
