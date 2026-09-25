/**
 * The stored JSON value, or `undefined` when nothing readable is stored; broken JSON is dropped.
 * Blocked or full storage never breaks the page: these helpers swallow its errors.
 */
export function readRecord(storage: Storage, key: string): { readonly value: unknown } | undefined {
  let raw: string | null;
  try {
    raw = storage.getItem(key);
  } catch {
    return undefined;
  }
  if (raw === null) return undefined;
  try {
    return { value: JSON.parse(raw) as unknown };
  } catch {
    removeRecord(storage, key);
    return undefined;
  }
}

export function writeRecord(storage: Storage, key: string, value: unknown): boolean {
  try {
    storage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

export function removeRecord(storage: Storage, key: string): boolean {
  try {
    storage.removeItem(key);
    return true;
  } catch {
    return false;
  }
}

/** A plain object with exactly these keys, so an old or tampered record is never half-trusted. */
export function hasExactKeys(
  value: unknown,
  keys: readonly string[],
): value is Record<string, unknown> {
  return typeof value === "object"
    && value !== null
    && !Array.isArray(value)
    && Object.keys(value).sort().join(",") === [...keys].sort().join(",");
}
