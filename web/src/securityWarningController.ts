import type { BrowserSecurityWarningPreferenceStore } from "./browserSecurityWarningPreferenceStore";
import type { CertificateTrust } from "./certificateTrustProbe";
import { required } from "./dom";

/** How a page served over HTTPS in secure mode checks that the browser trusts the phone. */
export interface SecureConnection {
  probeTrust(): Promise<CertificateTrust>;
  /** Where the certificate setup page is, for a browser that does not trust the phone. */
  readonly setupUrl: string;
}

const TRUST_PROBE_TIMEOUT_MS = 3_000;

export interface SecurityWarningController {
  start(): void;
  dispose(): void;
}

const EXPANDED_LABEL =
  "Используйте только в доверенной сети. Свернуть предупреждение";
const COLLAPSED_LABEL =
  "Используйте только в доверенной сети. Развернуть предупреждение";

/**
 * Over plain HTTP the page warns that traffic is not encrypted. Over HTTPS, in the phone's
 * secure mode, a short note says the connection is encrypted, unless the browser only got
 * here by clicking through a certificate warning: then it warns that the phone's certificate
 * is missing and links to the setup page.
 */
export function createSecurityWarningController(
  documentRef: Document,
  store: BrowserSecurityWarningPreferenceStore,
  secureConnection: SecureConnection | null = null,
): SecurityWarningController {
  const warning = required<HTMLButtonElement>(
    documentRef,
    '[data-role="security-warning"]',
    "security warning",
  );
  const detail = required<HTMLElement>(
    documentRef,
    '[data-role="security-warning-detail"]',
    "security warning",
  );
  const announcer = required<HTMLElement>(
    documentRef,
    '[data-role="preference-announcer"]',
    "security warning",
  );
  let started = false;

  const render = (expanded: boolean): void => {
    warning.dataset.state = expanded ? "expanded" : "collapsed";
    warning.setAttribute("aria-expanded", String(expanded));
    warning.setAttribute("aria-label", expanded ? EXPANDED_LABEL : COLLAPSED_LABEL);
    detail.hidden = !expanded;
  };
  const onToggle = (): void => {
    const expanded = warning.getAttribute("aria-expanded") === "true";
    if (expanded) {
      store.dismiss();
      render(false);
      announcer.textContent = "Предупреждение о доверенной сети свёрнуто.";
    } else {
      store.restore();
      render(true);
      announcer.textContent = "Предупреждение о доверенной сети раскрыто.";
    }
  };

  return {
    start(): void {
      if (started) return;
      started = true;
      if (secureConnection !== null) {
        warning.hidden = true;
        void showSecureConnection(documentRef, secureConnection);
        return;
      }
      warning.addEventListener("click", onToggle);
      render(!store.isDismissed());
    },
    dispose(): void {
      if (!started) return;
      started = false;
      warning.removeEventListener("click", onToggle);
    },
  };
}

async function showSecureConnection(
  documentRef: Document,
  connection: SecureConnection,
): Promise<void> {
  const trust = await Promise.race([
    connection.probeTrust().catch((): CertificateTrust => "unknown"),
    new Promise<CertificateTrust>((resolve) => setTimeout(() => resolve("unknown"), TRUST_PROBE_TIMEOUT_MS)),
  ]);
  if (trust === "untrusted") {
    required<HTMLAnchorElement>(
      documentRef,
      '[data-role="install-certificate-link"]',
      "security warning",
    ).href =
      connection.setupUrl;
    required<HTMLElement>(
      documentRef,
      '[data-role="untrusted-certificate-note"]',
      "security warning",
    ).hidden = false;
  } else {
    required<HTMLElement>(
      documentRef,
      '[data-role="secure-connection-note"]',
      "security warning",
    ).hidden = false;
  }
}