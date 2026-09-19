import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./integration",
  testMatch: "shell-layout.visual.spec.ts",
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  timeout: 20_000,
  expect: {
    toHaveScreenshot: {
      animations: "disabled",
      maxDiffPixelRatio: 0.005,
    },
  },
  use: {
    baseURL: "http://127.0.0.1:4175",
    browserName: "chromium",
    channel: "chrome",
    colorScheme: "dark",
    reducedMotion: "reduce",
    headless: true,
    trace: "retain-on-failure",
  },
  webServer: {
    command: "npm run dev:visual",
    url: "http://127.0.0.1:4175/",
    reuseExistingServer: false,
  },
  projects: [
    { name: "chrome-360", use: { viewport: { width: 360, height: 800 } } },
    {
      name: "chrome-360-light",
      use: { viewport: { width: 360, height: 800 }, colorScheme: "light" },
    },
    { name: "chrome-768", use: { viewport: { width: 768, height: 1024 } } },
    {
      name: "chrome-768-light",
      use: { viewport: { width: 768, height: 1024 }, colorScheme: "light" },
    },
    { name: "chrome-1920", use: { viewport: { width: 1920, height: 1080 } } },
    {
      name: "chrome-1920-light",
      use: { viewport: { width: 1920, height: 1080 }, colorScheme: "light" },
    },
    {
      name: "chrome-1920-zoom-200",
      use: {
        viewport: { width: 960, height: 540 },
        deviceScaleFactor: 2,
      },
    },
    {
      name: "chrome-1920-zoom-200-light",
      use: {
        viewport: { width: 960, height: 540 },
        deviceScaleFactor: 2,
        colorScheme: "light",
      },
    },
    {
      name: "edge-1920",
      use: { channel: "msedge", viewport: { width: 1920, height: 1080 } },
    },
    {
      name: "edge-1920-light",
      use: {
        channel: "msedge",
        viewport: { width: 1920, height: 1080 },
        colorScheme: "light",
      },
    },
  ],
});
