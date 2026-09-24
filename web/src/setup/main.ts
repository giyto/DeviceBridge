import { BrowserThemePreferenceStore } from "../browserThemePreferenceStore";
import { createDocumentThemeApplication, ThemeController } from "../themeController";
import { createSetupPage } from "./setupPage";
import { detectSetupPlatform } from "./setupPlatform";
import { createTrustProbe, httpsOriginOf } from "./trustProbe";

new ThemeController(
  new BrowserThemePreferenceStore(),
  createDocumentThemeApplication(document),
  window,
).start();

const httpsOrigin = httpsOriginOf(window.location);
createSetupPage(document, {
  platform: detectSetupPlatform(navigator.userAgent),
  probe: createTrustProbe((input, init) => fetch(input, init), httpsOrigin),
  openSecurePage: () => window.location.replace(httpsOrigin + "/"),
  rememberedBypass: new URLSearchParams(window.location.search).has("untrusted"),
}).start();
