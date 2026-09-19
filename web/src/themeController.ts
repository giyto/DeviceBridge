import {
  THEME_PREFERENCE_STORAGE_KEY,
  type BrowserThemePreferenceStore,
  type ThemePreference,
} from "./browserThemePreferenceStore";

export type EffectiveTheme = ThemePreference;
export interface ThemeApplication {
  readonly preference: ThemePreference;
  readonly effective: EffectiveTheme;
}

type ThemeStorageEvents = Pick<Window, "addEventListener" | "removeEventListener">;

export function createDocumentThemeApplication(
  documentRef: Document,
): (application: ThemeApplication) => void {
  return ({ preference, effective }): void => {
    const root = documentRef.documentElement;
    root.dataset.theme = effective;
    root.dataset.themePreference = preference;
    root.style.colorScheme = effective;
    documentRef.querySelector<HTMLMetaElement>('meta[name="color-scheme"]')
      ?.setAttribute("content", effective);
  };
}

export class ThemeController {
  private preference: ThemePreference = "dark";
  private started = false;

  private readonly onStorage = (event: Event): void => {
    const storageEvent = event as StorageEvent;
    if (storageEvent.key !== null && storageEvent.key !== THEME_PREFERENCE_STORAGE_KEY) return;
    this.preference = this.store.read();
    this.render();
  };

  constructor(
    private readonly store: BrowserThemePreferenceStore,
    private readonly apply: (application: ThemeApplication) => void,
    private readonly storageEvents: ThemeStorageEvents,
  ) {}

  start(): void {
    if (this.started) return;
    this.started = true;
    this.preference = this.store.read();
    this.storageEvents.addEventListener("storage", this.onStorage);
    this.render();
  }

  setPreference(preference: ThemePreference): void {
    this.preference = preference;
    this.store.save(preference);
    this.render();
  }

  dispose(): void {
    if (!this.started) return;
    this.started = false;
    this.storageEvents.removeEventListener("storage", this.onStorage);
  }

  private render(): void {
    this.apply({ preference: this.preference, effective: this.preference });
  }
}
