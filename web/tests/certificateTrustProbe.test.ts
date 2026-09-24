import { describe, expect, it, vi } from "vitest";
import {
  certificateSetupUrl,
  probeCertificateTrust,
  type WorkerRegistrar,
} from "../src/certificateTrustProbe";

const refusing = (message: string): WorkerRegistrar => ({
  register: vi.fn(async () => {
    throw new DOMException(message, "SecurityError");
  }),
});

describe("certificate trust probe", () => {
  it("reads Chromium's refusal after a click-through as untrusted", async () => {
    await expect(
      probeCertificateTrust(refusing(
        "Failed to register a ServiceWorker for scope ('https://10.0.2.16:8787/assets/') with script " +
          "('https://10.0.2.16:8787/web-manifest.json'): An SSL certificate error occurred when fetching the script.",
      )),
    ).resolves.toBe("untrusted");
  });

  it("reads the refusal of a trusted page as trusted", async () => {
    await expect(
      probeCertificateTrust(refusing(
        "Failed to register a ServiceWorker for scope ('https://10.0.2.16:8787/assets/') with script " +
          "('https://10.0.2.16:8787/web-manifest.json'): The script has an unsupported MIME type ('application/json').",
      )),
    ).resolves.toBe("trusted");
  });

  it("treats other browsers and a missing API as unknown", async () => {
    await expect(
      probeCertificateTrust(refusing("Bad Content-Type of 'application/json' received for script")),
    ).resolves.toBe("unknown");
    await expect(probeCertificateTrust(undefined)).resolves.toBe("unknown");
  });

  it("asks only for the manifest, which can never become a worker, and undoes any surprise", async () => {
    const unregister = vi.fn(async () => true);
    const registrar: WorkerRegistrar = { register: vi.fn(async () => ({ unregister })) };

    await expect(probeCertificateTrust(registrar)).resolves.toBe("unknown");

    expect(registrar.register).toHaveBeenCalledWith("/web-manifest.json", { scope: "/assets/" });
    expect(unregister).toHaveBeenCalledTimes(1);
  });

  it("points to the plain setup page of the same host", () => {
    expect(certificateSetupUrl({ href: "https://192.168.1.24:8787/some/path?x=1#y" }))
      .toBe("http://192.168.1.24:8787/?untrusted=1");
  });
});
