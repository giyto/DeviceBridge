import { isSessionToken, requireSessionToken } from "./protocolGuards";

const STORAGE_KEY = "devicebridge.session.v1";

export class BrowserSessionTokenStore {
  constructor(private readonly storage: Storage = globalThis.sessionStorage) {}

  read(): string | undefined {
    const token = this.storage.getItem(STORAGE_KEY) ?? undefined;
    if (token === undefined) return undefined;
    if (!isSessionToken(token)) {
      this.clear();
      return undefined;
    }
    return token;
  }

  save(token: string): void {
    requireSessionToken(token);
    this.storage.setItem(STORAGE_KEY, token);
  }

  clear(): void {
    this.storage.removeItem(STORAGE_KEY);
  }
}
