import { createHash } from "node:crypto";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { defineConfig, type Plugin } from "vite";

const webRoot = import.meta.dirname;

export default defineConfig({
  base: "/",
  plugins: [webManifestPlugin(computeWebAssetVersion())],
  build: {
    assetsDir: "assets",
    emptyOutDir: true,
    manifest: "asset-manifest.json",
    outDir: resolve(webRoot, "../app/src/main/assets/web"),
    sourcemap: false,
  },
});

function webManifestPlugin(webAssetVersion: string): Plugin {
  return {
    name: "devicebridge-web-manifest",
    apply: "build",
    generateBundle() {
      this.emitFile({
        type: "asset",
        fileName: ".gitkeep",
        source: "Generated web assets are intentionally not committed.\n",
      });
      this.emitFile({
        type: "asset",
        fileName: "web-manifest.json",
        source: `${JSON.stringify(
          {
            protocolVersion: 1,
            webAssetVersion,
          },
          null,
          2,
        )}\n`,
      });
    },
  };
}

function computeWebAssetVersion(): string {
  const hash = createHash("sha256");
  const inputPaths = [
    "index.html",
    "package-lock.json",
    "package.json",
    "src",
    "tsconfig.json",
    "vite.config.ts",
  ].flatMap((path) => collectFiles(resolve(webRoot, path)));

  for (const path of inputPaths.sort()) {
    hash.update(path.slice(webRoot.length).replaceAll("\\", "/"));
    hash.update("\0");
    hash.update(readFileSync(path));
    hash.update("\0");
  }

  return `sha256-${hash.digest("hex").slice(0, 16)}`;
}

function collectFiles(path: string): string[] {
  if (!statSync(path).isDirectory()) {
    return [path];
  }
  return readdirSync(path).flatMap((name) => collectFiles(resolve(path, name)));
}
