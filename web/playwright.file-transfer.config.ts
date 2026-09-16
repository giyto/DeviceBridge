import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./integration",
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
