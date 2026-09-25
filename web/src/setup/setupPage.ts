import { required } from "../dom";
import { SETUP_PLATFORMS, type SetupPlatform } from "./setupPlatform";

export interface SetupPageDependencies {
  readonly platform: SetupPlatform;
  readonly probe: () => Promise<boolean>;
  /** Leaves for the HTTPS page once the browser trusts the phone. */
  readonly openSecurePage: () => void;
  /**
   * Opened from the HTTPS page's missing-certificate warning. The browser then remembers
   * clicking through the warning, which also lets the trust check pass, so the page must not
   * check or leave until the browser has been restarted.
   */
  readonly rememberedBypass?: boolean;
}

export interface SetupPage {
  start(): void;
  check(): Promise<boolean>;
}

type TrustState = "checking" | "untrusted" | "trusted" | "bypassed";

const TEXT: Record<TrustState, { title: string; detail: string }> = {
  bypassed: {
    title: "Браузер открыл защищённую страницу без сертификата",
    detail:
      "Установите сертификат по инструкции ниже, затем полностью закройте браузер " +
      "(все окна) и снова откройте адрес, который показывает телефон.",
  },
  checking: {
    title: "Проверяем, доверяет ли браузер телефону…",
    detail: "Это займёт несколько секунд.",
  },
  untrusted: {
    title: "Сертификат телефона ещё не установлен",
    detail:
      "Скачайте и установите его по инструкции ниже, затем нажмите «Проверить снова». " +
      "Если сертификат уже установлен, перезапустите браузер.",
  },
  trusted: {
    title: "Браузер доверяет телефону",
    detail: "Открываем защищённую страницу DeviceBridge…",
  },
};

export function createSetupPage(documentRef: Document, deps: SetupPageDependencies): SetupPage {
  const status = required<HTMLElement>(documentRef, '[data-role="trust-status"]', "setup page");
  const title = required<HTMLElement>(documentRef, '[data-role="trust-title"]', "setup page");
  const detail = required<HTMLElement>(documentRef, '[data-role="trust-detail"]', "setup page");
  const checkAgain = required<HTMLButtonElement>(
    documentRef,
    '[data-action="check-again"]',
    "setup page",
  );
  const tabs = new Map(
    SETUP_PLATFORMS.map((platform) => [
      platform,
      required<HTMLButtonElement>(documentRef, `[data-platform="${platform}"]`, "setup page"),
    ]),
  );
  const guides = new Map(
    SETUP_PLATFORMS.map((platform) => [
      platform,
      required<HTMLElement>(documentRef, `[data-guide="${platform}"]`, "setup page"),
    ]),
  );
  let checking: Promise<boolean> | null = null;

  const render = (state: TrustState): void => {
    status.dataset.state = state;
    title.textContent = TEXT[state].title;
    detail.textContent = TEXT[state].detail;
    checkAgain.disabled = state !== "untrusted";
    checkAgain.hidden = state === "trusted" || state === "bypassed";
  };

  const select = (platform: SetupPlatform): void => {
    for (const [candidate, tab] of tabs) {
      const selected = candidate === platform;
      tab.setAttribute("aria-selected", String(selected));
      tab.tabIndex = selected ? 0 : -1;
      const guide = guides.get(candidate);
      if (guide !== undefined) guide.hidden = !selected;
    }
  };

  const check = (): Promise<boolean> => {
    if (checking !== null) return checking;
    render("checking");
    checking = deps.probe().then((trusted) => {
      checking = null;
      render(trusted ? "trusted" : "untrusted");
      if (trusted) deps.openSecurePage();
      return trusted;
    });
    return checking;
  };

  return {
    start(): void {
      select(deps.platform);
      for (const [platform, tab] of tabs) {
        tab.addEventListener("click", () => select(platform));
      }
      if (deps.rememberedBypass === true) {
        render("bypassed");
        return;
      }
      checkAgain.addEventListener("click", () => void check());
      // Coming back from the certificate dialog is the usual moment it starts to work.
      documentRef.addEventListener("visibilitychange", () => {
        if (documentRef.visibilityState === "visible") void check();
      });
      void check();
    },
    check,
  };
}
