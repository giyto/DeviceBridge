import type { EventNotificationPanelState } from "./eventNotificationController";

export interface EventNotificationViewCallbacks {
  readonly onEnable: () => void;
  readonly onDisable: () => void;
  readonly onHideContentChange: (hideContent: boolean) => void;
}

const STATUS_TEXT: Record<EventNotificationPanelState["kind"], string> = {
  unavailable:
    "Уведомления доступны в защищённом режиме. Его включают в настройках DeviceBridge на телефоне.",
  off: "Сообщат о тексте и файлах с телефона, пока вкладка свёрнута. Вкладка должна оставаться открытой.",
  requesting: "Разрешите уведомления в окне браузера.",
  enabled: "Включены. Приходят, пока вкладка DeviceBridge открыта, даже в свёрнутом окне.",
  denied: "Запрещены в браузере. Разрешите их через значок слева от адреса, раздел «Уведомления».",
};

export function createEventNotificationView(
  documentRef: Document,
  callbacks: EventNotificationViewCallbacks,
) {
  const panel = required<HTMLElement>(documentRef, '[data-role="notification-panel"]');
  const status = required<HTMLElement>(documentRef, '[data-role="notification-status"]');
  const enable = required<HTMLButtonElement>(documentRef, '[data-action="enable-notifications"]');
  const disable = required<HTMLButtonElement>(documentRef, '[data-action="disable-notifications"]');
  const options = required<HTMLElement>(documentRef, '[data-role="notification-options"]');
  const hideContent = required<HTMLInputElement>(documentRef, '[data-role="hide-notification-content"]');
  const announcer = required<HTMLElement>(documentRef, '[data-role="preference-announcer"]');
  let previous: EventNotificationPanelState | null = null;

  const onEnable = () => callbacks.onEnable();
  const onDisable = () => callbacks.onDisable();
  const onHideContent = () => callbacks.onHideContentChange(hideContent.checked);
  enable.addEventListener("click", onEnable);
  disable.addEventListener("click", onDisable);
  hideContent.addEventListener("change", onHideContent);

  return {
    render(state: EventNotificationPanelState): void {
      const focusedButton = [enable, disable].find((button) => button === documentRef.activeElement);
      panel.dataset.state = state.kind;
      status.textContent = STATUS_TEXT[state.kind];
      enable.hidden = state.kind !== "off" && state.kind !== "requesting";
      enable.disabled = state.kind === "requesting";
      const enabled = state.kind === "enabled";
      options.hidden = !enabled;
      disable.hidden = !enabled;
      if (enabled) hideContent.checked = state.hideContent;
      // The pressed button may have just disappeared; keep the keyboard in the panel.
      const replacement = enabled ? disable : enable;
      if (focusedButton?.hidden && !replacement.hidden) replacement.focus();
      const announcement = announcementFor(previous, state);
      if (announcement !== null) announcer.textContent = announcement;
      previous = state;
    },
    dispose(): void {
      enable.removeEventListener("click", onEnable);
      disable.removeEventListener("click", onDisable);
      hideContent.removeEventListener("change", onHideContent);
    },
  };
}

/** Says out loud what the person just changed; nothing on the first render. */
function announcementFor(
  previous: EventNotificationPanelState | null,
  next: EventNotificationPanelState,
): string | null {
  if (previous === null) return null;
  if (next.kind === "enabled" && previous.kind === "enabled") {
    if (next.hideContent === previous.hideContent) return null;
    return next.hideContent
      ? "Содержимое в уведомлениях скрыто"
      : "Содержимое показывается в уведомлениях";
  }
  if (next.kind === previous.kind) return null;
  if (next.kind === "enabled") return "Уведомления включены";
  if (next.kind === "denied") return "Уведомления запрещены в браузере";
  if (next.kind === "off" && previous.kind === "enabled") return "Уведомления выключены";
  return null;
}

function required<T extends Element>(documentRef: Document, selector: string): T {
  const element = documentRef.querySelector<T>(selector);
  if (element === null) throw new Error("Missing DeviceBridge notification element: " + selector);
  return element;
}
