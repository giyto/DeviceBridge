import type { ThemePreference } from "./browserThemePreferenceStore";
import { required } from "./dom";

export interface ThemeControl {
  render(theme: ThemePreference): void;
  dispose(): void;
}

export function createThemeControl(
  documentRef: Document,
  onPreferenceChange: (preference: ThemePreference) => void,
): ThemeControl {
  const toggle = required<HTMLButtonElement>(
    documentRef,
    'button[data-role="theme-control"]',
    "theme control",
  );
  const sun = required<SVGElement & { hidden: boolean }>(
    documentRef,
    '[data-theme-icon="sun"]',
    "theme control",
  );
  const moon = required<SVGElement & { hidden: boolean }>(
    documentRef,
    '[data-theme-icon="moon"]',
    "theme control",
  );
  let activeTheme: ThemePreference = "dark";

  const onClick = (): void => {
    onPreferenceChange(activeTheme === "dark" ? "light" : "dark");
  };
  toggle.addEventListener("click", onClick);

  return {
    render(theme): void {
      activeTheme = theme;
      toggle.dataset.activeTheme = theme;
      const nextLabel = theme === "dark"
        ? "Включить светлую тему"
        : "Включить тёмную тему";
      toggle.setAttribute("aria-label", nextLabel);
      toggle.title = nextLabel;
      sun.hidden = theme !== "light";
      moon.hidden = theme !== "dark";
    },
    dispose(): void {
      toggle.removeEventListener("click", onClick);
    },
  };
}
