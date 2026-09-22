import { createHash } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const outputRoot = resolve(process.cwd(), "../app/src/main/assets/web");

describe("Vite Android asset output", () => {
  it("emits hashed JavaScript and CSS referenced by current HTML", () => {
    const html = readFileSync(resolve(outputRoot, "index.html"), "utf8");
    const assets = readdirSync(resolve(outputRoot, "assets")).sort();

    expect(assets.some((path) => /^index-[A-Za-z0-9_-]{8,}\.js$/.test(path))).toBe(true);
    expect(assets.some((path) => /^index-[A-Za-z0-9_-]{8,}\.css$/.test(path))).toBe(true);
    for (const asset of assets) {
      expect(html).toContain(`/assets/${asset}`);
    }
  });

  it("keeps the synchronous theme bootstrap covered by the release CSP hash", () => {
    const html = readFileSync(resolve(outputRoot, "index.html"), "utf8");
    const bootstrap = html.match(
      /<script data-role="theme-bootstrap">([\s\S]*?)<\/script>/,
    )?.[1];

    expect(bootstrap).toBeDefined();
    expect(createHash("sha256").update(bootstrap ?? "").digest("base64")).toBe(
      "F5EhQ4Xw10HZb4yMlvyeV6RnFM6rJV0SIN4Hvm5VCkc=",
    );
  });

  it("emits an exact, deterministic public web manifest", () => {
    const manifest = JSON.parse(
      readFileSync(resolve(outputRoot, "web-manifest.json"), "utf8"),
    ) as Record<string, unknown>;

    expect(Object.keys(manifest).sort()).toEqual([
      "protocolVersion",
      "webAssetVersion",
    ]);
    expect(manifest).toMatchObject({
      protocolVersion: 1,
      webAssetVersion: expect.stringMatching(/^sha256-[a-f0-9]{16}$/),
    });
  });
});
