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
    expect(source).toMatch(
      /new SessionController\([\s\S]*notifyingTextSession,[\s\S]*notifyingFileSession,/,
    );
    // The sessions handed to SessionController still deliver every event to the controllers.
    for (const call of [
      "textController.receive(event)",
      "textController.applySnapshot(event)",
      "textController.receiveError(event)",
      "fileController.receiveOffer(event)",
      "fileController.receiveProgress(event)",
      "fileController.applySnapshot(event)",
      "fileController.receiveError(event)",
    ]) {
      expect(source).toContain(call);
    }
    expect(source).toContain("fileController.dispose()");
    expect(source).toContain("fileView.dispose()");
  });
  it("wires visual preferences independently from session and transfer commands", () => {
    const source = readFileSync(resolve(process.cwd(), "src/main.ts"), "utf8");
    const themeSource = readFileSync(
      resolve(process.cwd(), "src/themeController.ts"),
      "utf8",
    );
    const warningSource = readFileSync(
      resolve(process.cwd(), "src/securityWarningController.ts"),
      "utf8",
    );
    const notificationSource = readFileSync(
      resolve(process.cwd(), "src/eventNotificationController.ts"),
      "utf8",
    );

    expect(source).toContain("new BrowserThemePreferenceStore()");
    expect(source).toContain("new ThemeController(");
    expect(source).toContain("createThemeControl(");
    expect(source).toContain("new BrowserSecurityWarningPreferenceStore()");
    expect(source).toContain("createSecurityWarningController(");
    expect(source.indexOf("themeController.start()"))
      .toBeLessThan(source.indexOf("new SessionController("));
    expect(source).toContain("themeController.dispose()");
    expect(source).toContain("securityWarningController.dispose()");
    expect(source).toContain("new BrowserNotificationPreferenceStore()");
    expect(source).toContain("notificationController.start()");
    expect(source).toContain("notificationController.dispose()");
    expect(themeSource + warningSource + notificationSource).not.toMatch(
      /SessionController|TextTransferController|FileTransferController/,
    );
  });
});
