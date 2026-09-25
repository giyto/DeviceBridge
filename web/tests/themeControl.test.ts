// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from "vitest";
import { createThemeControl } from "../src/themeControl";

describe("theme control", () => {
  beforeEach(() => {
    document.body.innerHTML = `
      <button
        type="button"
        data-role="theme-control"
        aria-label="Включить светлую тему"
      >
        <svg data-theme-icon="sun" aria-hidden="true"></svg>
        <svg data-theme-icon="moon" aria-hidden="true"></svg>
      </button>
    `;
  });

  it("renders one icon for the active theme and names the next action", () => {
    const onPreferenceChange = vi.fn();
    const control = createThemeControl(document, onPreferenceChange);
    const toggle = button();
    const sun = icon("sun");
    const moon = icon("moon");

    control.render("dark");
    expect(toggle.dataset.activeTheme).toBe("dark");
    expect(toggle.getAttribute("aria-label")).toBe("Включить светлую тему");
    expect(toggle.title).toBe("Включить светлую тему");
    expect(sun.hidden).toBe(true);
    expect(moon.hidden).toBe(false);

    toggle.click();
    expect(onPreferenceChange).toHaveBeenCalledOnce();
    expect(onPreferenceChange).toHaveBeenCalledWith("light");

    control.render("light");
    expect(toggle.dataset.activeTheme).toBe("light");
    expect(toggle.getAttribute("aria-label")).toBe("Включить тёмную тему");
    expect(sun.hidden).toBe(false);
    expect(moon.hidden).toBe(true);

    toggle.click();
    expect(onPreferenceChange).toHaveBeenLastCalledWith("dark");

    control.dispose();
    toggle.click();
    expect(onPreferenceChange).toHaveBeenCalledTimes(2);
  });
});

function button(): HTMLButtonElement {
  return document.querySelector<HTMLButtonElement>('button[data-role="theme-control"]')!;
}

function icon(name: "sun" | "moon"): SVGElement & { hidden: boolean } {
  return document.querySelector<SVGElement & { hidden: boolean }>(
    `[data-theme-icon="${name}"]`,
  )!;
}
