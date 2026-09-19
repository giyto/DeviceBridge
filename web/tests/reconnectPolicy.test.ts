import { describe, expect, it } from "vitest";
import { BoundedReconnectPolicy } from "../src/reconnectPolicy";

describe("BoundedReconnectPolicy", () => {
  it("uses a bounded exponential schedule with an injectable jitter seam", () => {
    const low = new BoundedReconnectPolicy({ random: () => 0 });
    const middle = new BoundedReconnectPolicy({ random: () => 0.5 });
    const high = new BoundedReconnectPolicy({ random: () => 1 });

    expect([0, 1, 2].map((attempt) => low.delayForAttempt(attempt)))
      .toEqual([800, 1_600, 3_200]);
    expect([0, 1, 2].map((attempt) => middle.delayForAttempt(attempt)))
      .toEqual([1_000, 2_000, 4_000]);
    expect([0, 1, 2].map((attempt) => high.delayForAttempt(attempt)))
      .toEqual([1_200, 2_400, 4_800]);
    expect(middle.delayForAttempt(3)).toBeUndefined();
  });
});
