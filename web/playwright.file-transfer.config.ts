import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./integration",
  testMatch: ["file-transfer-gate.spec.ts", "file-draft-editing.spec.ts"],
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  timeout: 20_000,
  use: {
    acceptDownloads: true,
    headless: true,
    launchOptions: {
      args: ["--enable-precise-memory-info"],
    },
    trace: "retain-on-failure",
  },
  webServer: {
    command: "npm run dev:file-transfer-gate",
    url: "http://127.0.0.1:4174/integration/file-draft-harness.html",
    reuseExistingServer: false,
  },
  projects: [
    {
      name: "chrome",
      use: { channel: "chrome" },
    },
    {
      name: "edge",
      use: { channel: "msedge" },
    },
  ],
});
