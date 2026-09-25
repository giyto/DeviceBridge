import {
  THEME_PREFERENCE_STORAGE_KEY,
  type BrowserThemePreferenceStore,
  type ThemePreference,
} from "./browserThemePreferenceStore";

type ThemeStorageEvents = Pick<Window, "addEventListener" | "removeEventListener">;

export function createDocumentThemeApplication(
  documentRef: Document,
): (theme: ThemePreference) => void {
  return (theme): void => {
    const root = documentRef.documentElement;
    root.dataset.theme = theme;
    root.dataset.themePreference = theme;
    root.style.colorScheme = theme;
    documentRef.querySelector<HTMLMetaElement>('meta[name="color-scheme"]')
      ?.setAttribute("content", theme);
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
    private readonly apply: (theme: ThemePreference) => void,
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
    this.apply(this.preference);
  }
}
