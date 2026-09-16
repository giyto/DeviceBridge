export class NativeFileDownloader {
  constructor(private readonly documentRef: Document = document) {}

  start(downloadPath: string, displayName: string): void {
    validateDownloadPath(downloadPath);
    const anchor = this.documentRef.createElement("a");
    anchor.href = downloadPath;
    anchor.download = safeDownloadName(displayName);
    anchor.referrerPolicy = "no-referrer";
    anchor.rel = "noreferrer";
    anchor.hidden = true;
    this.documentRef.body.append(anchor);
    try {
      anchor.click();
    } finally {
      anchor.remove();
    }
  }
}

function validateDownloadPath(path: string): void {
  if (
    path.includes("://") ||
    path.includes("#") ||
    path.length > 512
  ) {
    throw new Error("Unsafe DeviceBridge download path");
  }
  const match = /^\/api\/v1\/files\/[A-Za-z0-9_-]{1,64}\?(.+)$/.exec(path);
  if (match === null) {
    throw new Error("Unsafe DeviceBridge download path");
  }
  const parameters = new URLSearchParams(match[1]);
  const grants = parameters.getAll("grant");
  const grant = grants[0];
  if (grants.length !== 1 || grant === undefined || grant.length < 1 || grant.length > 256) {
    throw new Error("Invalid DeviceBridge download grant");
  }
  for (const key of parameters.keys()) {
    if (key.toLowerCase() !== "grant") {
      throw new Error("Unexpected DeviceBridge download credential");
    }
  }
}

function safeDownloadName(value: string): string {
  const leaf = value.split(/[\\/]/).at(-1) ?? "";
  const safe = [...leaf]
    .filter((character) => {
      const code = character.codePointAt(0) ?? 0;
      return code > 0x1f && code !== 0x7f && !BIDI_CONTROLS.has(code);
    })
    .join("")
    .trim()
    .slice(0, 180);
  return safe.length > 0 ? safe : "devicebridge-file";
}

const BIDI_CONTROLS = new Set([
  0x061c, 0x200e, 0x200f,
  0x202a, 0x202b, 0x202c, 0x202d, 0x202e,
  0x2066, 0x2067, 0x2068, 0x2069,
]);
