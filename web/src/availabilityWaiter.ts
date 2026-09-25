import type { Scheduler } from "./scheduler";

/** Whether the tab is on screen, and a way to hear when that changes. */
export interface VisibilityPort {
  isVisible(): boolean;
  onChange(listener: () => void): () => void;
}

/** The browser saying the network is back. */
export interface OnlinePort {
  onOnline(listener: () => void): () => void;
}

export const VISIBLE_CHECK_DELAY_MS = 3_000;
export const HIDDEN_CHECK_DELAY_MS = 15_000;

/**
 * Waits for the phone after the quick retries gave up: it asks the public manifest every few
 * seconds, less often while the tab is hidden, and right away when the tab comes back, the
 * network returns or the person asks. Only one question is in flight at a time.
 */
export class AvailabilityWaiter {
  private active = false;
  private inFlight = false;
  private timer?: unknown;
  private abort?: AbortController;
  private onAvailable?: () => void;
  private unsubscribe: Array<() => void> = [];

  constructor(
    private readonly probe: (signal: AbortSignal) => Promise<boolean>,
    private readonly scheduler: Scheduler,
    private readonly visibility: VisibilityPort,
    private readonly online: OnlinePort,
  ) {}

  get waiting(): boolean {
    return this.active;
  }

  start(onAvailable: () => void): void {
    this.stop();
    this.active = true;
    this.onAvailable = onAvailable;
    this.unsubscribe = [
      this.visibility.onChange(() => {
        if (!this.active) return;
        if (this.visibility.isVisible()) this.checkNow();
        else this.schedule();
      }),
      this.online.onOnline(() => this.checkNow()),
    ];
    this.schedule();
  }

  checkNow(): void {
    if (!this.active || this.inFlight) return;
    this.clearTimer();
    this.inFlight = true;
    const controller = new AbortController();
    this.abort = controller;
    void this.probe(controller.signal)
      .catch(() => false)
      .then((available) => {
        if (this.abort !== controller) return;
        this.abort = undefined;
        this.inFlight = false;
        if (!this.active) return;
        if (available) {
          const callback = this.onAvailable;
          this.stop();
          callback?.();
        } else {
          this.schedule();
        }
      });
  }

  stop(): void {
    this.active = false;
    this.inFlight = false;
    this.onAvailable = undefined;
    this.clearTimer();
    this.abort?.abort();
    this.abort = undefined;
    for (const unsubscribe of this.unsubscribe) unsubscribe();
    this.unsubscribe = [];
  }

  private schedule(): void {
    this.clearTimer();
    if (this.inFlight) return;
    const delayMs = this.visibility.isVisible() ? VISIBLE_CHECK_DELAY_MS : HIDDEN_CHECK_DELAY_MS;
    this.timer = this.scheduler.setTimeout(() => {
      this.timer = undefined;
      this.checkNow();
    }, delayMs);
  }

  private clearTimer(): void {
    if (this.timer === undefined) return;
    this.scheduler.clearTimeout(this.timer);
    this.timer = undefined;
  }
}

export function documentVisibilityPort(documentRef: Document): VisibilityPort {
  return {
    isVisible: () => documentRef.visibilityState !== "hidden",
    onChange: (listener) => {
      documentRef.addEventListener("visibilitychange", listener);
      return () => documentRef.removeEventListener("visibilitychange", listener);
    },
  };
}

export function windowOnlinePort(windowRef: Window): OnlinePort {
  return {
    onOnline: (listener) => {
      windowRef.addEventListener("online", listener);
      return () => windowRef.removeEventListener("online", listener);
    },
  };
}
