import type { BrowserSecurityWarningPreferenceStore } from "./browserSecurityWarningPreferenceStore";

export interface SecurityWarningController {
  start(): void;
  dispose(): void;
}

const EXPANDED_LABEL =
  "Используйте только в доверенной сети. Свернуть предупреждение";
const COLLAPSED_LABEL =
  "Используйте только в доверенной сети. Развернуть предупреждение";

export function createSecurityWarningController(
  documentRef: Document,
  store: BrowserSecurityWarningPreferenceStore,
): SecurityWarningController {
  const warning = required<HTMLButtonElement>(
    documentRef,
    '[data-role="security-warning"]',
  );
  const detail = required<HTMLElement>(
    documentRef,
    '[data-role="security-warning-detail"]',
  );
  const announcer = required<HTMLElement>(
    documentRef,
    '[data-role="preference-announcer"]',
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

function required<T extends Element>(documentRef: Document, selector: string): T {
  const element = documentRef.querySelector<T>(selector);
  if (element === null) throw new Error('Missing security warning element: ' + selector);
  return element;
}