export type SetupPlatform = "windows" | "macos" | "firefox" | "linux";

export const SETUP_PLATFORMS: readonly SetupPlatform[] = ["windows", "macos", "firefox", "linux"];

/**
 * Picks the instructions most likely to apply. Firefox comes first because it keeps its own
 * certificate store on every system; the others trust what the operating system trusts.
 */
export function detectSetupPlatform(userAgent: string): SetupPlatform {
  if (/Firefox\//.test(userAgent)) return "firefox";
  if (/Windows/.test(userAgent)) return "windows";
  if (/Macintosh|Mac OS X/.test(userAgent)) return "macos";
  if (/Linux|X11|CrOS/.test(userAgent)) return "linux";
  return "windows";
}
