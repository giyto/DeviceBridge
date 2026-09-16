import { SessionApiClient } from "./sessionApiClient";
import { SessionController } from "./sessionController";
import { SessionEventSocketClient } from "./sessionEventSocketClient";
import { BrowserSessionTokenStore } from "./sessionTokenStore";
import { createShellView } from "./shellView";
import { WebManifestClient } from "./webManifestClient";

let controller: SessionController;
const view = createShellView(document, {
  onRetry: () => controller.retry(),
  onSubmitCode: (code) => controller.submitCode(code),
  onDisconnect: () => controller.disconnect(),
});

controller = new SessionController(
  new WebManifestClient(),
  new SessionApiClient(),
  new BrowserSessionTokenStore(),
  new SessionEventSocketClient(),
  (state) => view.render(state),
  browserLabel(navigator.userAgent),
);
controller.start();

globalThis.addEventListener(
  "beforeunload",
  () => {
    controller.dispose();
    view.dispose();
  },
  { once: true },
);

function browserLabel(userAgent: string): string {
  const browser = userAgent.includes("Edg/")
    ? "Edge"
    : userAgent.includes("Firefox/")
      ? "Firefox"
      : userAgent.includes("Chrome/")
        ? "Chrome"
        : userAgent.includes("Safari/")
          ? "Safari"
          : "Browser";
  const platform = navigator.platform.trim() || "Computer";
  return `${browser} on ${platform}`.slice(0, 64);
}
