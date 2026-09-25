import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AvailabilityWaiter,
  HIDDEN_CHECK_DELAY_MS,
  VISIBLE_CHECK_DELAY_MS,
} from "../src/availabilityWaiter";

afterEach(() => vi.useRealTimers());

describe("AvailabilityWaiter", () => {
  it("asks every 3 seconds while visible and stops once the phone answers", async () => {
    const fixture = setup();
    fixture.probe.mockResolvedValueOnce(false).mockResolvedValueOnce(true);

    fixture.waiter.start(fixture.onAvailable);
    await vi.advanceTimersByTimeAsync(VISIBLE_CHECK_DELAY_MS - 1);
    expect(fixture.probe).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(fixture.probe).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(VISIBLE_CHECK_DELAY_MS);

    expect(fixture.probe).toHaveBeenCalledTimes(2);
    expect(fixture.onAvailable).toHaveBeenCalledOnce();
    expect(fixture.waiter.waiting).toBe(false);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fixture.probe).toHaveBeenCalledTimes(2);
  });

  it("asks every 15 seconds while hidden and right away when the tab comes back", async () => {
    const fixture = setup(false);
    fixture.probe.mockResolvedValue(false);

    fixture.waiter.start(fixture.onAvailable);
    await vi.advanceTimersByTimeAsync(HIDDEN_CHECK_DELAY_MS - 1);
    expect(fixture.probe).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(fixture.probe).toHaveBeenCalledTimes(1);

    fixture.setVisible(true);
    await vi.advanceTimersByTimeAsync(0);
    expect(fixture.probe).toHaveBeenCalledTimes(2);
  });

  it("hiding the tab moves a pending check to the slow pace", async () => {
    const fixture = setup(true);
    fixture.probe.mockResolvedValue(false);

    fixture.waiter.start(fixture.onAvailable);
    fixture.setVisible(false);
    await vi.advanceTimersByTimeAsync(VISIBLE_CHECK_DELAY_MS);
    expect(fixture.probe).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(HIDDEN_CHECK_DELAY_MS - VISIBLE_CHECK_DELAY_MS);
    expect(fixture.probe).toHaveBeenCalledTimes(1);
  });

  it("the network coming back checks at once, but only one question is in flight", async () => {
    const fixture = setup();
    let answer: (value: boolean) => void = () => undefined;
    fixture.probe.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));

    fixture.waiter.start(fixture.onAvailable);
    fixture.goOnline();
    fixture.waiter.checkNow();
    fixture.setVisible(true);
    await vi.advanceTimersByTimeAsync(0);
    expect(fixture.probe).toHaveBeenCalledTimes(1);

    answer(true);
    await vi.advanceTimersByTimeAsync(0);
    expect(fixture.onAvailable).toHaveBeenCalledOnce();
  });

  it("a failing probe counts as not yet and stop cancels everything", async () => {
    const fixture = setup();
    fixture.probe.mockRejectedValue(new TypeError("offline"));

    fixture.waiter.start(fixture.onAvailable);
    await vi.advanceTimersByTimeAsync(VISIBLE_CHECK_DELAY_MS);
    expect(fixture.probe).toHaveBeenCalledTimes(1);
    fixture.waiter.stop();
    fixture.goOnline();
    await vi.advanceTimersByTimeAsync(60_000);

    expect(fixture.probe).toHaveBeenCalledTimes(1);
    expect(fixture.onAvailable).not.toHaveBeenCalled();
  });
});

function setup(initiallyVisible = true) {
  vi.useFakeTimers();
  let visible = initiallyVisible;
  const visibilityListeners = new Set<() => void>();
  const onlineListeners = new Set<() => void>();
  const probe = vi.fn<(signal: AbortSignal) => Promise<boolean>>();
  const onAvailable = vi.fn();
  const waiter = new AvailabilityWaiter(
    probe,
    {
      setTimeout: (callback, delayMs) => globalThis.setTimeout(callback, delayMs),
      clearTimeout: (handle) => globalThis.clearTimeout(handle as number),
    },
    {
      isVisible: () => visible,
      onChange: (listener) => {
        visibilityListeners.add(listener);
        return () => visibilityListeners.delete(listener);
      },
    },
    {
      onOnline: (listener) => {
        onlineListeners.add(listener);
        return () => onlineListeners.delete(listener);
      },
    },
  );
  return {
    waiter,
    probe,
    onAvailable,
    setVisible(value: boolean) {
      visible = value;
      for (const listener of [...visibilityListeners]) listener();
    },
    goOnline() {
      for (const listener of [...onlineListeners]) listener();
    },
  };
}
