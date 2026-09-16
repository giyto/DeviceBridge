import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("file transfer application wiring", () => {
  it("connects the file view, streaming adapters and session lifecycle in main", () => {
    const source = readFileSync(resolve(process.cwd(), "src/main.ts"), "utf8");

    expect(source).toContain("createFileTransferView");
    expect(source).toContain("new FileTransferController(");
    expect(source).toContain("new XhrFileUploader()");
    expect(source).toContain("new NativeFileDownloader(document)");
    expect(source).toContain("hashFileStreaming");
    expect(source).toMatch(/new SessionController\([\s\S]*textController,[\s\S]*fileController,/);
    expect(source).toContain("fileController.dispose()");
    expect(source).toContain("fileView.dispose()");
  });
});
