import type { ThemePreference } from "./browserThemePreferenceStore";
import type { ThemeApplication } from "./themeController";

export interface ThemeControl {
  render(application: ThemeApplication): void;
  dispose(): void;
}

export function createThemeControl(
  documentRef: Document,
  onPreferenceChange: (preference: ThemePreference) => void,
): ThemeControl {
  const toggle = required<HTMLButtonElement>(documentRef, 'button[data-role="theme-control"]');
  const sun = required<SVGElement & { hidden: boolean }>(
    documentRef,
    '[data-theme-icon="sun"]',
  );
  const moon = required<SVGElement & { hidden: boolean }>(
    documentRef,
    '[data-theme-icon="moon"]',
  );
  let activeTheme: ThemePreference = "dark";

  const onClick = (): void => {
    onPreferenceChange(activeTheme === "dark" ? "light" : "dark");
  };
  toggle.addEventListener("click", onClick);

  return {
    render({ effective }): void {
      activeTheme = effective;
      toggle.dataset.activeTheme = effective;
      const nextLabel = effective === "dark"
        ? "Включить светлую тему"
        : "Включить тёмную тему";
      toggle.setAttribute("aria-label", nextLabel);
      toggle.title = nextLabel;
      sun.hidden = effective !== "light";
      moon.hidden = effective !== "dark";
    },
    dispose(): void {
      toggle.removeEventListener("click", onClick);
    },
  };
}

function required<T extends Element>(documentRef: Document, selector: string): T {
  const element = documentRef.querySelector<T>(selector);
  if (element === null) throw new Error(`Missing theme control element: ${selector}`);
  return element;
}
