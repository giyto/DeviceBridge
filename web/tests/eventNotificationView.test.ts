// @vitest-environment jsdom

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createEventNotificationView } from "../src/eventNotificationView";

beforeEach(() => {
  document.open();
  document.write(readFileSync(resolve(process.cwd(), "index.html"), "utf8"));
  document.close();
});

describe("notification panel", () => {
  it("only hints at secure mode on a plain HTTP page", () => {
    const view = createEventNotificationView(document, callbacks());

    view.render({ kind: "unavailable" });

    expect(panel().dataset.state).toBe("unavailable");
    expect(status()).toContain("Уведомления доступны в защищённом режиме");
    expect(enableButton().hidden).toBe(true);
    expect(disableButton().hidden).toBe(true);
    expect(options().hidden).toBe(true);
  });

  it("offers to turn them on and waits while the browser asks", () => {
    const actions = callbacks();
    const view = createEventNotificationView(document, actions);

    view.render({ kind: "off" });
    expect(enableButton().hidden).toBe(false);
    expect(status()).toContain("Вкладка должна оставаться открытой");
    enableButton().click();
    expect(actions.onEnable).toHaveBeenCalledOnce();

    view.render({ kind: "requesting" });
    expect(enableButton().disabled).toBe(true);
  });

  it("shows the content switch and the way to turn them off once enabled", () => {
    const actions = callbacks();
    const view = createEventNotificationView(document, actions);
    view.render({ kind: "off" });

    view.render({ kind: "enabled", hideContent: true });

    expect(enableButton().hidden).toBe(true);
    expect(options().hidden).toBe(false);
    expect(hideContent().checked).toBe(true);
    expect(announcer()).toBe("Уведомления включены");

    hideContent().click();
    expect(actions.onHideContentChange).toHaveBeenCalledWith(false);
    disableButton().click();
    expect(actions.onDisable).toHaveBeenCalledOnce();
  });

  it("announces changes but not the first render", () => {
    const view = createEventNotificationView(document, callbacks());

    view.render({ kind: "enabled", hideContent: false });
    expect(announcer()).toBe("");

    view.render({ kind: "enabled", hideContent: true });
    expect(announcer()).toBe("Содержимое в уведомлениях скрыто");

    view.render({ kind: "off" });
    expect(announcer()).toBe("Уведомления выключены");
  });

  it("keeps keyboard focus in the panel when the pressed button disappears", () => {
    const view = createEventNotificationView(document, callbacks());
    view.render({ kind: "enabled", hideContent: false });
    disableButton().focus();

    view.render({ kind: "off" });

    expect(document.activeElement).toBe(enableButton());
  });

  it("explains a refusal without offering a button that cannot work", () => {
    const view = createEventNotificationView(document, callbacks());
    view.render({ kind: "off" });

    view.render({ kind: "denied" });

    expect(status()).toContain("Запрещены в браузере");
    expect(enableButton().hidden).toBe(true);
    expect(announcer()).toBe("Уведомления запрещены в браузере");
  });
});

function callbacks() {
  return { onEnable: vi.fn(), onDisable: vi.fn(), onHideContentChange: vi.fn() };
}

const panel = () => document.querySelector<HTMLElement>('[data-role="notification-panel"]')!;
const status = () => document.querySelector('[data-role="notification-status"]')!.textContent ?? "";
const enableButton = () => document.querySelector<HTMLButtonElement>('[data-action="enable-notifications"]')!;
const disableButton = () => document.querySelector<HTMLButtonElement>('[data-action="disable-notifications"]')!;
const options = () => document.querySelector<HTMLElement>('[data-role="notification-options"]')!;
const hideContent = () => document.querySelector<HTMLInputElement>('[data-role="hide-notification-content"]')!;
const announcer = () => document.querySelector('[data-role="preference-announcer"]')!.textContent ?? "";
