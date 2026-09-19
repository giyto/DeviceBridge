import { describe, expect, it } from "vitest";
import {
  failureCatalog,
  resolveFailure,
} from "../src/failureCatalog";
import { failureContractFixture } from "./fixtures/failureContractFixture";

describe("failure catalog contract", () => {
  it("covers every stable wire code with matching severity and recovery actions", () => {
    expect(Object.keys(failureCatalog).sort()).toEqual(
      Object.keys(failureContractFixture).sort(),
    );

    for (const [code, expected] of Object.entries(failureContractFixture)) {
      const actual = resolveFailure(code);
      expect(actual.code).toBe(code);
      expect(actual.severity).toBe(expected.severity);
      expect(actual.recoveryActions).toEqual(expected.actions);
      expect(actual.title.trim().length).toBeGreaterThan(0);
      expect(actual.message.trim().length).toBeGreaterThan(0);
      expect(actual.title).not.toBe(code);
    }
  });

  it("uses a safe actionable fallback for a forward-compatible unknown code", () => {
    const failure = resolveFailure("future_server_failure");

    expect(failure).toMatchObject({
      code: "unknown_error",
      severity: "recoverable",
      recoveryActions: ["retry"],
    });
    expect(failure.title).not.toContain("future_server_failure");
    expect(failure.message).not.toContain("future_server_failure");
  });
});
