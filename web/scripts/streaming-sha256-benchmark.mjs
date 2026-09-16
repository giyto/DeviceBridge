import { gzipSync } from "node:zlib";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { build } from "vite";

const webRoot = resolve(import.meta.dirname, "..");
const packageMetadata = JSON.parse(
  await readFile(
    resolve(webRoot, "node_modules/@noble/hashes/package.json"),
    "utf8",
  ),
);
const buildResult = await build({
  configFile: false,
  logLevel: "silent",
  build: {
    emptyOutDir: false,
    lib: {
      entry: resolve(webRoot, "src/streamingSha256.ts"),
      fileName: "streaming-sha256",
      formats: ["es"],
    },
    minify: "oxc",
    write: false,
  },
});
const outputs = Array.isArray(buildResult) ? buildResult : [buildResult];
const chunk = outputs
  .flatMap((output) => output.output)
  .find((output) => output.type === "chunk");
if (!chunk || chunk.type !== "chunk") {
  throw new Error("Streaming SHA-256 benchmark bundle was not generated");
}

const rawBytes = Buffer.byteLength(chunk.code);
const gzipBytes = gzipSync(chunk.code).byteLength;
const result = {
  adapter: "@noble/hashes",
  version: packageMetadata.version,
  license: packageMetadata.license,
  rawBytes,
  gzipBytes,
  gzipLimitBytes: 4_096,
};
process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);

if (result.license !== "MIT") {
  throw new Error(`Unexpected adapter license: ${result.license}`);
}
if (gzipBytes > result.gzipLimitBytes) {
  throw new Error(
    `Streaming SHA-256 bundle is ${gzipBytes} bytes gzip; limit is ${result.gzipLimitBytes}`,
  );
}
