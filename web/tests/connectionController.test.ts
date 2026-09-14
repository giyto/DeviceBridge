import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ConnectionController,
  type ConnectionState,
  type ManifestLoader,
} from "../src/connectionController";
import type { WebManifest } from "../src/webManifestClient";

const manifest: WebManifest = {
  protocolVersion: 1,
  webAssetVersion: "sha256-test",
};

afterEach(() => {
  vi.useRealTimers();
});

describe("ConnectionController", () => {
  it("moves from checking to available", async () => {
    const loader: ManifestLoader = { load: vi.fn().mockResolvedValue(manifest) };
    const states: ConnectionState[] = [];
    const controller = new ConnectionController(loader, (state) => states.push(state));

    controller.start();
    await vi.waitFor(() => expect(states.at(-1)?.kind).toBe("available"));

    expect(states.map((state) => state.kind)).toEqual(["checking", "available"]);
    expect(states.at(-1)).toEqual({ kind: "available", manifest });
  });

  it("retries after 1, 2 and 4 seconds, then stops automatically", async () => {
    vi.useFakeTimers();
    const loader: ManifestLoader = {
      load: vi.fn().mockRejectedValue(new Error("offline")),
    };
    const states: ConnectionState[] = [];
    const controller = new ConnectionController(loader, (state) => states.push(state));

    controller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(states.at(-1)).toMatchObject({ kind: "unavailable", nextRetryInMs: 1_000 });

    await vi.advanceTimersByTimeAsync(1_000);
    expect(states.at(-1)).toMatchObject({ kind: "unavailable", nextRetryInMs: 2_000 });

    await vi.advanceTimersByTimeAsync(2_000);
    expect(states.at(-1)).toMatchObject({ kind: "unavailable", nextRetryInMs: 4_000 });

    await vi.advanceTimersByTimeAsync(4_000);
    expect(states.at(-1)).toEqual({
      kind: "unavailable",
      message: "Не удаётся связаться с DeviceBridge.",
    });
    expect(loader.load).toHaveBeenCalledTimes(4);

    await vi.advanceTimersByTimeAsync(60_000);
    expect(loader.load).toHaveBeenCalledTimes(4);
  });

  it("manual retry cancels the active request and starts a new check", async () => {
    let firstSignal: AbortSignal | undefined;
    const loader: ManifestLoader = {
      load: vi
        .fn()
        .mockImplementationOnce((signal?: AbortSignal) => {
          firstSignal = signal;
          return new Promise<WebManifest>((_resolve, reject) => {
            signal?.addEventListener("abort", () => {
              reject(new DOMException("Aborted", "AbortError"));
            });
          });
        })
        .mockResolvedValueOnce(manifest),
    };
    const states: ConnectionState[] = [];
    const controller = new ConnectionController(loader, (state) => states.push(state));

    controller.start();
    controller.retry();
    await vi.waitFor(() => expect(states.at(-1)?.kind).toBe("available"));

    expect(firstSignal?.aborted).toBe(true);
    expect(loader.load).toHaveBeenCalledTimes(2);
    expect(states.at(-1)).toEqual({ kind: "available", manifest });
  });

  it("dispose cancels an active request and all scheduled retries", async () => {
    vi.useFakeTimers();
    const loader: ManifestLoader = {
      load: vi.fn().mockRejectedValue(new Error("offline")),
    };
    const controller = new ConnectionController(loader, () => undefined);

    controller.start();
    await vi.advanceTimersByTimeAsync(0);
    controller.dispose();
    await vi.advanceTimersByTimeAsync(60_000);

    expect(loader.load).toHaveBeenCalledOnce();
  });
});
