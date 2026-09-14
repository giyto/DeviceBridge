import { readFileSync, readdirSync, statSync } from "node:fs";
import { relative, resolve } from "node:path";
import { describe, expect, it } from "vitest";

const outputRoot = resolve(process.cwd(), "../app/src/main/assets/web");

describe("built web privacy contract", () => {
  it("contains no external runtime URL, analytics or service worker", () => {
    const textOutputs = outputFiles()
      .filter((path) => /\.(?:html|css|js|json)$/.test(path))
      .map((path) => readFileSync(resolve(outputRoot, path), "utf8"))
      .join("\n");

    expect(textOutputs).not.toMatch(/https?:\/\//i);
    expect(textOutputs).not.toMatch(/(?:googletagmanager|google-analytics|segment\.io|sentry\.io)/i);
    expect(textOutputs).not.toMatch(/(?:navigator\.)?serviceWorker|service-worker/i);
  });

  it("contains only entry files and assets declared by the Vite manifest", () => {
    const manifest = JSON.parse(
      readFileSync(resolve(outputRoot, "asset-manifest.json"), "utf8"),
    ) as Record<string, ManifestEntry>;
    const declared = new Set<string>([
      ".gitkeep",
      "asset-manifest.json",
      "index.html",
      "web-manifest.json",
    ]);

    for (const entry of Object.values(manifest)) {
      declared.add(entry.file);
      entry.css?.forEach((path) => declared.add(path));
      entry.assets?.forEach((path) => declared.add(path));
    }

    expect(outputFiles().filter((path) => !declared.has(path))).toEqual([]);
  });

  it("keeps every browser request on the DeviceBridge origin", () => {
    const html = readFileSync(resolve(outputRoot, "index.html"), "utf8");
    const runtimeUrls = [...html.matchAll(/(?:src|href)="([^"]+)"/g)].map(
      (match) => match[1] ?? "",
    );

    expect(runtimeUrls.length).toBeGreaterThan(0);
    expect(
      runtimeUrls.every((url) => url.startsWith("/") || url.startsWith("#")),
    ).toBe(true);
  });
});

interface ManifestEntry {
  readonly file: string;
  readonly css?: readonly string[];
  readonly assets?: readonly string[];
}

function outputFiles(directory = outputRoot): string[] {
  return readdirSync(directory)
    .flatMap((name) => {
      const path = resolve(directory, name);
      return statSync(path).isDirectory() ? outputFiles(path) : [normalize(relative(outputRoot, path))];
    })
    .sort();
}

function normalize(path: string): string {
  return path.replaceAll("\\", "/");
}
