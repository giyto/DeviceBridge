export type ProbeFetch = (input: string, init: RequestInit) => Promise<unknown>;

export const TLS_PROBE_PATH = "/api/v1/tls-probe";
const DEFAULT_TIMEOUT_MS = 5_000;

/**
 * Asks the phone over HTTPS whether the browser accepts its certificate. The request is
 * cross-origin from this plain HTTP page, so it is sent `no-cors`: the answer is opaque, but
 * it arrives only when the TLS handshake succeeded, which is all this needs to know.
 */
export function createTrustProbe(
  fetchFn: ProbeFetch,
  httpsOrigin: string,
  timeoutMs: number = DEFAULT_TIMEOUT_MS,
): () => Promise<boolean> {
  return async () => {
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), timeoutMs);
    try {
      await fetchFn(httpsOrigin + TLS_PROBE_PATH, {
        mode: "no-cors",
        cache: "no-store",
        credentials: "omit",
        signal: abort.signal,
      });
      return true;
    } catch {
      return false;
    } finally {
      clearTimeout(timer);
    }
  };
}

/**
 * The HTTPS origin of the page's own address; secure mode serves both on one port. Built by
 * switching the scheme so the bundle carries no absolute URL of its own.
 */
export function httpsOriginOf(location: Pick<Location, "href">): string {
  const url = new URL(location.href);
  url.protocol = "https:";
  return url.origin;
}
