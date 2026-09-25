import { isProtocolId } from "./protocolGuards";
import type { TextDraftStore } from "./textTransferController";

const STORAGE_KEY = "devicebridge.textDraft.v1";
const MAX_DRAFT_CHARACTERS = 102_400;

type DraftRecord = Readonly<{ scopeId: string; draft: string }>;

export class BrowserTextDraftStore implements TextDraftStore {
  constructor(private readonly storage: Storage = globalThis.sessionStorage) {}

  read(scopeId: string): string | undefined {
    const record = this.readRecord();
    if (record === undefined) return undefined;
    if (record.scopeId !== scopeId) {
      this.storage.removeItem(STORAGE_KEY);
      return undefined;
    }
    return record.draft;
  }

  save(scopeId: string, draft: string): void {
    if (draft.length === 0) {
      this.clear(scopeId);
      return;
    }
    if (!isProtocolId(scopeId) || draft.length > MAX_DRAFT_CHARACTERS) return;
    this.storage.setItem(STORAGE_KEY, JSON.stringify({ scopeId, draft } satisfies DraftRecord));
  }

  clear(scopeId: string): void {
    const record = this.readRecord();
    if (record === undefined || record.scopeId === scopeId) {
      this.storage.removeItem(STORAGE_KEY);
    }
  }

  private readRecord(): DraftRecord | undefined {
    try {
      const raw = this.storage.getItem(STORAGE_KEY);
      if (raw === null) return undefined;
      const value = JSON.parse(raw) as unknown;
      if (
        typeof value !== "object" || value === null ||
        !("scopeId" in value) || !("draft" in value) ||
        !isProtocolId(value.scopeId) || typeof value.draft !== "string" ||
        value.draft.length === 0 || value.draft.length > MAX_DRAFT_CHARACTERS
      ) {
        this.storage.removeItem(STORAGE_KEY);
        return undefined;
      }
      return { scopeId: value.scopeId, draft: value.draft };
    } catch {
      this.storage.removeItem(STORAGE_KEY);
      return undefined;
    }
  }
}
