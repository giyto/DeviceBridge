import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("interface preference module boundaries", () => {
  it.each([
    "browserThemePreferenceStore.ts",
    "themeController.ts",
    "browserSecurityWarningPreferenceStore.ts",
    "securityWarningController.ts",
  ])("provides isolated %s", (fileName) => {
    expect(existsSync(resolve(process.cwd(), "src", fileName))).toBe(true);
  });
});
