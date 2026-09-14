import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

const targetUrl = process.env.DEVICEBRIDGE_URL ?? "http://127.0.0.1:8787/";
const browserCandidates = [
  {
    name: "Chrome",
    configuredPath: process.env.DEVICEBRIDGE_CHROME_PATH,
    defaults: [
      "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
      "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
      join(process.env.LOCALAPPDATA ?? "", "Google", "Chrome", "Application", "chrome.exe"),
    ],
  },
  {
    name: "Edge",
    configuredPath: process.env.DEVICEBRIDGE_EDGE_PATH,
    defaults: [
      "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
      "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    ],
  },
];

const scenarios = [
  { width: 360, height: 900, theme: "light" },
  { width: 360, height: 900, theme: "dark" },
  { width: 768, height: 900, theme: "light" },
  { width: 768, height: 900, theme: "dark" },
  { width: 1920, height: 1080, theme: "light" },
  { width: 1920, height: 1080, theme: "dark" },
];

const browsers = browserCandidates.map((candidate) => ({
  name: candidate.name,
  path: resolveBrowserPath(candidate),
}));

const results = [];
for (const browser of browsers) {
  results.push(await runBrowser(browser));
}

const report = {
  targetUrl,
  generatedAt: new Date().toISOString(),
  browsers: results,
};

const failures = results.flatMap((browser) =>
  browser.scenarios.filter(
    (scenario) =>
      scenario.statusState !== "available" ||
      !scenario.warningVisible ||
      !scenario.noHorizontalOverflow ||
      !scenario.keyboardFocusVisible ||
      scenario.loadMs > 5_000 ||
      scenario.externalRequests.length > 0 ||
      scenario.serviceWorkerCount !== 0,
  ),
);

process.stdout.write(JSON.stringify(report, null, 2) + "\n");
if (failures.length > 0) {
  process.exitCode = 1;
}

function resolveBrowserPath(candidate) {
  const paths = [candidate.configuredPath, ...candidate.defaults].filter(Boolean);
  const resolved = paths.find((path) => existsSync(path));
  if (!resolved) {
    throw new Error(candidate.name + " executable was not found. Configure DEVICEBRIDGE_" + candidate.name.toUpperCase() + "_PATH.");
  }
  return resolved;
}

async function runBrowser(browser) {
  const profile = await mkdtemp(join(tmpdir(), "devicebridge-browser-"));
  const processHandle = spawn(
    browser.path,
    [
      "--headless=new",
      "--disable-background-networking",
      "--disable-component-update",
      "--disable-default-apps",
      "--disable-extensions",
      "--disable-sync",
      "--no-first-run",
      "--no-default-browser-check",
      "--remote-debugging-port=0",
      "--user-data-dir=" + profile,
      "about:blank",
    ],
    { stdio: "ignore", windowsHide: true },
  );

  let cdp;
  try {
    const port = await waitForDebuggingPort(profile, processHandle);
    const pages = await fetch("http://127.0.0.1:" + port + "/json/list").then((response) => response.json());
    const page = pages.find((candidate) => candidate.type === "page");
    if (!page?.webSocketDebuggerUrl) {
      throw new Error(browser.name + " did not expose a debuggable page");
    }

    cdp = await connectCdp(page.webSocketDebuggerUrl);
    const version = await cdp.send("Browser.getVersion");
    await cdp.send("Page.enable");
    await cdp.send("Network.enable");
    await cdp.send("Network.setCacheDisabled", { cacheDisabled: true });

    const scenarioResults = [];
    for (const scenario of scenarios) {
      scenarioResults.push(await runScenario(cdp, scenario));
    }

    return {
      name: browser.name,
      product: version.product,
      userAgent: version.userAgent,
      scenarios: scenarioResults,
    };
  } finally {
    cdp?.close();
    processHandle.kill();
    if (!profile.startsWith(tmpdir())) {
      throw new Error("Refusing to remove an unexpected browser profile path: " + profile);
    }
    await delay(250);
    await rm(profile, { recursive: true, force: true });
  }
}

async function runScenario(cdp, scenario) {
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    width: scenario.width,
    height: scenario.height,
    deviceScaleFactor: 1,
    mobile: scenario.width <= 768,
  });
  await cdp.send("Emulation.setEmulatedMedia", {
    media: "screen",
    features: [{ name: "prefers-color-scheme", value: scenario.theme }],
  });
  await cdp.send("Network.clearBrowserCache");

  const requests = [];
  const stopListening = cdp.on("Network.requestWillBeSent", (event) => {
    requests.push(event.request.url);
  });

  const startedAt = Date.now();
  await cdp.send("Page.navigate", { url: targetUrl });
  await waitForAvailableState(cdp);
  const loadMs = Date.now() - startedAt;
  stopListening();

  await cdp.send("Runtime.evaluate", {
    expression: "document.activeElement && document.activeElement.blur()",
  });
  await cdp.send("Input.dispatchKeyEvent", {
    type: "rawKeyDown",
    key: "Tab",
    code: "Tab",
    windowsVirtualKeyCode: 9,
  });
  await cdp.send("Input.dispatchKeyEvent", {
    type: "keyUp",
    key: "Tab",
    code: "Tab",
    windowsVirtualKeyCode: 9,
  });

  const evaluation = await cdp.send("Runtime.evaluate", {
    expression: "(async () => { const warning = document.querySelector('[data-role=security-warning]'); const focused = document.activeElement; return { title: document.title, statusState: document.querySelector('[data-role=status]')?.dataset.state ?? null, statusText: document.querySelector('[data-role=status-title]')?.textContent?.trim() ?? null, warningVisible: Boolean(warning && warning.getClientRects().length > 0), warningText: warning?.textContent?.replace(/\\s+/g, ' ').trim() ?? null, viewportWidth: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth, noHorizontalOverflow: document.documentElement.scrollWidth <= document.documentElement.clientWidth, mediaDark: matchMedia('(prefers-color-scheme: dark)').matches, backgroundColor: getComputedStyle(document.body).backgroundColor, focusedTag: focused?.tagName ?? null, focusedText: focused?.textContent?.trim() ?? null, keyboardFocusVisible: Boolean(focused?.matches(':focus-visible')), serviceWorkerCount: navigator.serviceWorker ? (await navigator.serviceWorker.getRegistrations()).length : 0 }; })()",
    awaitPromise: true,
    returnByValue: true,
  });

  const origin = new URL(targetUrl).origin;
  const localRequests = [...new Set(requests.filter((url) => url.startsWith(origin)))];
  const externalRequests = [...new Set(requests.filter((url) => url.startsWith("http") && !url.startsWith(origin)))];

  return {
    ...scenario,
    ...evaluation.result.value,
    loadMs,
    localRequests,
    externalRequests,
  };
}

async function waitForAvailableState(cdp) {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    const result = await cdp.send("Runtime.evaluate", {
      expression: "({ ready: document.readyState, state: document.querySelector('[data-role=status]')?.dataset.state ?? null })",
      returnByValue: true,
    });
    if (result.result.value.ready === "complete" && result.result.value.state === "available") {
      return;
    }
    await delay(50);
  }
  throw new Error("DeviceBridge did not reach the available state within 5 seconds");
}

async function waitForDebuggingPort(profile, processHandle) {
  const portFile = join(profile, "DevToolsActivePort");
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (processHandle.exitCode !== null) {
      throw new Error("Browser exited before exposing DevTools, code " + processHandle.exitCode);
    }
    if (existsSync(portFile)) {
      const [port] = (await readFile(portFile, "utf8")).split(/\r?\n/);
      if (port) {
        return Number(port);
      }
    }
    await delay(100);
  }
  throw new Error("Timed out waiting for the browser DevTools port");
}

function connectCdp(url) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    const pending = new Map();
    const listeners = new Map();
    let nextId = 0;

    socket.addEventListener("error", () => reject(new Error("Unable to connect to browser DevTools")), { once: true });
    socket.addEventListener("open", () => {
      socket.addEventListener("message", (event) => {
        const message = JSON.parse(String(event.data));
        if (message.id !== undefined) {
          const handler = pending.get(message.id);
          pending.delete(message.id);
          if (!handler) return;
          if (message.error) handler.reject(new Error(message.error.message));
          else handler.resolve(message.result);
          return;
        }
        for (const listener of listeners.get(message.method) ?? []) {
          listener(message.params);
        }
      });

      resolve({
        send(method, params = {}) {
          return new Promise((resolveCommand, rejectCommand) => {
            const id = ++nextId;
            pending.set(id, { resolve: resolveCommand, reject: rejectCommand });
            socket.send(JSON.stringify({ id, method, params }));
          });
        },
        on(method, listener) {
          const methodListeners = listeners.get(method) ?? [];
          methodListeners.push(listener);
          listeners.set(method, methodListeners);
          return () => listeners.set(method, methodListeners.filter((candidate) => candidate !== listener));
        },
        close() {
          socket.close();
        },
      });
    }, { once: true });
  });
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
