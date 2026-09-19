import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./integration",
  testMatch: "browser-compatibility.spec.ts",
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  timeout: 30_000,
  use: {
    baseURL: "http://127.0.0.1:4176",
    headless: true,
    locale: "ru-RU",
    viewport: { width: 1440, height: 900 },
    trace: "retain-on-failure",
  },
  webServer: {
    command: "npm run dev:compatibility",
    url: "http://127.0.0.1:4176/",
    reuseExistingServer: false,
  },
  projects: [
    { name: "chrome-windows", use: { channel: "chrome" } },
    { name: "edge-windows", use: { channel: "msedge" } },
  ],
});
