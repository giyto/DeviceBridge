import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ManifestCompatibilityError,
  ManifestFormatError,
  ManifestTimeoutError,
  WebManifestClient,
} from "../src/webManifestClient";

afterEach(() => {
  vi.useRealTimers();
});

describe("WebManifestClient", () => {
  it("loads and validates a same-origin manifest", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({
        protocolVersion: 1,
        webAssetVersion: "sha256-a1b2c3",
      }),
    );
    const client = new WebManifestClient({ fetcher, timeoutMs: 1_000 });

    await expect(client.load()).resolves.toEqual({
      protocolVersion: 1,
      webAssetVersion: "sha256-a1b2c3",
    });
    expect(fetcher).toHaveBeenCalledOnce();
    expect(fetcher).toHaveBeenCalledWith(
      "/web-manifest.json",
      expect.objectContaining({
        cache: "no-store",
        credentials: "same-origin",
        method: "GET",
        redirect: "error",
      }),
    );
    expect(fetcher.mock.calls[0]?.[1]?.signal).toBeInstanceOf(AbortSignal);
  });

  it("rejects an unsupported protocol version", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(Response.json({ protocolVersion: 2, webAssetVersion: "next" }));
    const client = new WebManifestClient({ fetcher, timeoutMs: 1_000 });

    await expect(client.load()).rejects.toBeInstanceOf(ManifestCompatibilityError);
  });

  it.each([
    { protocolVersion: 1 },
    { protocolVersion: "1", webAssetVersion: "broken" },
    { protocolVersion: 1, webAssetVersion: "" },
    { protocolVersion: 1, webAssetVersion: "ok", token: "must-not-exist" },
  ])("rejects an invalid or expanded manifest: %j", async (payload) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json(payload));
    const client = new WebManifestClient({ fetcher, timeoutMs: 1_000 });

    await expect(client.load()).rejects.toBeInstanceOf(ManifestFormatError);
  });

  it("aborts a request when the timeout expires", async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn<typeof fetch>((_, init) => {
      return new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => {
          reject(new DOMException("Aborted", "AbortError"));
        });
      });
    });
    const client = new WebManifestClient({ fetcher, timeoutMs: 750 });

    const pending = expect(client.load()).rejects.toBeInstanceOf(ManifestTimeoutError);
    await vi.advanceTimersByTimeAsync(750);

    await pending;
  });
});
