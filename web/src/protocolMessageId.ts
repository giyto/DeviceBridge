const RANDOM_BYTE_COUNT = 16;

export function createProtocolMessageId(
  cryptoProvider: Pick<Crypto, "getRandomValues"> = globalThis.crypto,
  now: () => number = () => Date.now(),
): string {
  const randomBytes = cryptoProvider.getRandomValues(new Uint8Array(RANDOM_BYTE_COUNT));
  const randomPart = Array.from(
    randomBytes,
    (byte) => byte.toString(16).padStart(2, "0"),
  ).join("");
  return `m_${now().toString(36)}_${randomPart}`;
}
