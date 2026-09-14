import type { WebManifest } from "./webManifestClient";

export type ConnectionState =
  | Readonly<{ kind: "checking" }>
  | Readonly<{ kind: "available"; manifest: WebManifest }>
  | Readonly<{
      kind: "unavailable";
      message: string;
      nextRetryInMs?: number;
    }>;

export interface ManifestLoader {
  load(signal?: AbortSignal): Promise<WebManifest>;
}

export interface RetryScheduler {
  setTimeout(callback: () => void, delayMs: number): unknown;
  clearTimeout(handle: unknown): void;
}

const RETRY_DELAYS_MS = [1_000, 2_000, 4_000] as const;
const UNAVAILABLE_MESSAGE = "Не удаётся связаться с DeviceBridge.";

const browserScheduler: RetryScheduler = {
  setTimeout: (callback, delayMs) => globalThis.setTimeout(callback, delayMs),
  clearTimeout: (handle) => globalThis.clearTimeout(handle as number),
};

export class ConnectionController {
  private generation = 0;
  private requestController: AbortController | undefined;
  private retryHandle: unknown;
  private disposed = false;

  constructor(
    private readonly loader: ManifestLoader,
    private readonly onStateChange: (state: ConnectionState) => void,
    private readonly scheduler: RetryScheduler = browserScheduler,
  ) {}

  start(): void {
    this.beginNewCycle();
  }

  retry(): void {
    this.beginNewCycle();
  }

  dispose(): void {
    this.disposed = true;
    this.generation += 1;
    this.cancelPendingWork();
  }

  private beginNewCycle(): void {
    this.disposed = false;
    this.generation += 1;
    this.cancelPendingWork();
    void this.runAttempt(this.generation, 0);
  }

  private async runAttempt(generation: number, retryIndex: number): Promise<void> {
    if (!this.isCurrent(generation)) {
      return;
    }

    const requestController = new AbortController();
    this.requestController = requestController;
    this.onStateChange({ kind: "checking" });

    try {
      const manifest = await this.loader.load(requestController.signal);
      if (this.isCurrent(generation)) {
        this.onStateChange({ kind: "available", manifest });
      }
    } catch (error: unknown) {
      if (!this.isCurrent(generation) || isAbortError(error)) {
        return;
      }

      const nextRetryInMs = RETRY_DELAYS_MS[retryIndex];
      if (nextRetryInMs === undefined) {
        this.onStateChange({
          kind: "unavailable",
          message: UNAVAILABLE_MESSAGE,
        });
        return;
      }

      this.onStateChange({
        kind: "unavailable",
        message: UNAVAILABLE_MESSAGE,
        nextRetryInMs,
      });
      this.retryHandle = this.scheduler.setTimeout(() => {
        this.retryHandle = undefined;
        void this.runAttempt(generation, retryIndex + 1);
      }, nextRetryInMs);
    } finally {
      if (this.requestController === requestController) {
        this.requestController = undefined;
      }
    }
  }

  private cancelPendingWork(): void {
    this.requestController?.abort();
    this.requestController = undefined;
    if (this.retryHandle !== undefined) {
      this.scheduler.clearTimeout(this.retryHandle);
      this.retryHandle = undefined;
    }
  }

  private isCurrent(generation: number): boolean {
    return !this.disposed && generation === this.generation;
  }
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}
