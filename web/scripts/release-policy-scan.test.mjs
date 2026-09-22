import assert from "node:assert/strict";
import test from "node:test";

import { scanText } from "./release-policy-scan.mjs";

test("safe local-only fixture produces no findings", () => {
  const source = `
    const endpoint = "http://" + host + ":" + port;
    const androidNamespace = "http://schemas.android.com/apk/res/android";
    const previewOnly = "https://example.com";
    const token = request.token;
    # https://docs.gradle.org/current/userguide/build_environment.html
  `;

  assert.deepEqual(scanText(source, "SafeFixture.kt"), []);
});

test("forbidden release fixture is blocked by every policy family", () => {
  const source = String.raw`
    -----BEGIN PRIVATE KEY-----
    const password = "hardcoded-secret-value";
    fetch("https://api.example.net/upload");
    const cdn = "https://cdn.jsdelivr.net/npm/example";
    navigator.serviceWorker.register("/sw.js");
    const path = "C:\Users\alice\private\payload.txt";
    console.log("token", token);
  `;

  assert.deepEqual(
    new Set(scanText(source, "ForbiddenFixture.ts").map((finding) => finding.rule)),
    new Set([
      "PRIVATE_KEY",
      "SECRET_LITERAL",
      "EXTERNAL_URL",
      "ANALYTICS_OR_CDN",
      "SERVICE_WORKER",
      "ABSOLUTE_LOCAL_PATH",
      "SENSITIVE_LOG",
    ]),
  );
});
