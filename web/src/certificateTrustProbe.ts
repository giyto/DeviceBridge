export type CertificateTrust = "trusted" | "untrusted" | "unknown";

/** The one part of the service worker API this page touches. */
export interface WorkerRegistrar {
  register(scriptUrl: string, options: { scope: string }): Promise<{ unregister(): Promise<boolean> }>;
}

/**
 * Tells whether the browser trusts the phone's certificate on this HTTPS page. Pages opened
 * after clicking through a certificate warning look like any secure context, but Chromium
 * refuses to fetch a service worker script on them, naming the certificate error. The script
 * here is the JSON web manifest, which can never become a worker, so nothing is registered:
 * only the reason for the refusal matters. Anything unrecognised is "unknown".
 */
export async function probeCertificateTrust(
  registrar: WorkerRegistrar | undefined,
): Promise<CertificateTrust> {
  if (registrar === undefined) return "unknown";
  try {
    const registration = await registrar.register("/web-manifest.json", { scope: "/assets/" });
    // Not expected, but never leave a worker behind.
    await registration.unregister().catch(() => false);
    return "unknown";
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (/SSL certificate error/i.test(message)) return "untrusted";
    if (/MIME type/i.test(message)) return "trusted";
    return "unknown";
  }
}

/** The page's own plain-HTTP address, where the certificate setup page lives. */
export function certificateSetupUrl(location: Pick<Location, "href">): string {
  const url = new URL(location.href);
  url.protocol = "http:";
  url.pathname = "/";
  url.search = "?untrusted=1";
  url.hash = "";
  return url.href;
}
