import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("DeviceBridge web shell", () => {
  it("has the HTML entry point expected by Vite", () => {
    expect(existsSync(resolve(process.cwd(), "index.html"))).toBe(true);
  });
});
