export const SUPPORTED_PROTOCOL_VERSION = 1;

export interface WebManifest {
  readonly protocolVersion: number;
  readonly webAssetVersion: string;
}

export class ManifestRequestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ManifestRequestError";
  }
}

export class ManifestTimeoutError extends ManifestRequestError {
  constructor() {
    super("DeviceBridge did not answer before the request timed out.");
    this.name = "ManifestTimeoutError";
  }
}

export class ManifestFormatError extends ManifestRequestError {
  constructor() {
    super("DeviceBridge returned an invalid web manifest.");
    this.name = "ManifestFormatError";
  }
}

export class ManifestCompatibilityError extends ManifestRequestError {
  constructor(readonly receivedVersion: number) {
    super(`Unsupported DeviceBridge protocol version: ${receivedVersion}.`);
    this.name = "ManifestCompatibilityError";
  }
}

interface WebManifestClientOptions {
  readonly fetcher?: typeof fetch;
  readonly timeoutMs?: number;
}

export class WebManifestClient {
  private readonly fetcher: typeof fetch;
  private readonly timeoutMs: number;

  constructor(options: WebManifestClientOptions = {}) {
    this.fetcher = options.fetcher ?? globalThis.fetch.bind(globalThis);
    this.timeoutMs = options.timeoutMs ?? 5_000;
  }

  async load(externalSignal?: AbortSignal): Promise<WebManifest> {
    const controller = new AbortController();
    let timedOut = false;
    const forwardAbort = () => controller.abort();
    if (externalSignal?.aborted) {
      forwardAbort();
    } else {
      externalSignal?.addEventListener("abort", forwardAbort, { once: true });
    }
    const timeoutId = globalThis.setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, this.timeoutMs);

    try {
      const response = await this.fetcher("/web-manifest.json", {
        cache: "no-store",
        credentials: "same-origin",
        method: "GET",
        redirect: "error",
        signal: controller.signal,
      });

      if (!response.ok) {
        throw new ManifestRequestError(
          `DeviceBridge returned HTTP ${response.status} for the web manifest.`,
        );
      }

      let payload: unknown;
      try {
        payload = await response.json();
      } catch {
        throw new ManifestFormatError();
      }

      return parseWebManifest(payload);
    } catch (error: unknown) {
      if (timedOut) {
        throw new ManifestTimeoutError();
      }
      throw error;
    } finally {
      globalThis.clearTimeout(timeoutId);
      externalSignal?.removeEventListener("abort", forwardAbort);
    }
  }
}

export function parseWebManifest(payload: unknown): WebManifest {
  if (!isPlainObject(payload)) {
    throw new ManifestFormatError();
  }

  const keys = Object.keys(payload).sort();
  if (
    keys.length !== 2 ||
    keys[0] !== "protocolVersion" ||
    keys[1] !== "webAssetVersion"
  ) {
    throw new ManifestFormatError();
  }

  const protocolVersion = payload.protocolVersion;
  const webAssetVersion = payload.webAssetVersion;
  if (
    !Number.isInteger(protocolVersion) ||
    typeof protocolVersion !== "number" ||
    typeof webAssetVersion !== "string" ||
    webAssetVersion.trim().length === 0 ||
    webAssetVersion.length > 128
  ) {
    throw new ManifestFormatError();
  }

  if (protocolVersion !== SUPPORTED_PROTOCOL_VERSION) {
    throw new ManifestCompatibilityError(protocolVersion);
  }

  return {
    protocolVersion,
    webAssetVersion,
  };
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value) &&
    Object.getPrototypeOf(value) === Object.prototype
  );
}
