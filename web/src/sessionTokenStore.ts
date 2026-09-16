const STORAGE_KEY = "devicebridge.session.v1";
const MAX_TOKEN_LENGTH = 256;

export class BrowserSessionTokenStore {
  constructor(private readonly storage: Storage = globalThis.sessionStorage) {}

  read(): string | undefined {
    const token = this.storage.getItem(STORAGE_KEY) ?? undefined;
    if (token === undefined) return undefined;
    if (!isValidToken(token)) {
      this.clear();
      return undefined;
    }
    return token;
  }

  save(token: string): void {
    if (!isValidToken(token)) throw new Error("Invalid session credential");
    this.storage.setItem(STORAGE_KEY, token);
  }

  clear(): void {
    this.storage.removeItem(STORAGE_KEY);
  }
}

function isValidToken(token: string): boolean {
  return token.length >= 1 && token.length <= MAX_TOKEN_LENGTH && !/\s/.test(token);
}
