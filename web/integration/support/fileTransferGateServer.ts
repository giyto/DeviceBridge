import { createHash, randomBytes } from "node:crypto";
import {
  createServer as createHttpServer,
  type IncomingMessage,
  type ServerResponse,
} from "node:http";
import { resolve } from "node:path";
import { createServer as createViteServer } from "vite";

const FILE_NAME = "devicebridge-fixture.bin";
const FILE_CONTENT = Buffer.from("DeviceBridge native download fixture\n", "utf8");

export interface FileTransferGateServer {
  close(): Promise<void>;
  fileName: string;
  fileSize: number;
  pageUrl: string;
  sha256: string;
}

export interface FileTransferGateServerOptions {
  grantTtlMs?: number;
}

export async function startFileTransferGateServer(
  options: FileTransferGateServerOptions = {},
): Promise<FileTransferGateServer> {
  const grant = randomBytes(16).toString("base64url");
  const grantIssuedAt = Date.now();
  const grantTtlMs = options.grantTtlMs ?? 30_000;
  let grantConsumed = false;
  let verificationAcks = 0;
  const eventStreams = new Set<ServerResponse>();
  const vite = await createViteServer({
    appType: "custom",
    logLevel: "silent",
    root: resolve(import.meta.dirname, "../.."),
    server: {
      hmr: false,
      middlewareMode: true,
    },
  });
  const server = createHttpServer((request, response) => {
    const url = new URL(request.url ?? "/", "http://127.0.0.1");

    if (request.method === "GET" && url.pathname === "/") {
      response.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      response.end(
        renderPage(
          grant,
          createHash("sha256").update(FILE_CONTENT).digest("hex"),
        ),
      );
      return;
    }

    if (request.method === "GET" && url.pathname === "/events") {
      response.writeHead(200, {
        "Cache-Control": "no-cache",
        "Content-Type": "text/event-stream",
      });
      response.write("event: ready\ndata: connected\n\n");
      eventStreams.add(response);
      request.once("close", () => eventStreams.delete(response));
      return;
    }

    if (request.method === "GET" && url.pathname === "/download") {
      if (url.searchParams.get("grant") !== grant) {
        response.writeHead(404).end();
        return;
      }
      if (grantConsumed || Date.now() - grantIssuedAt >= grantTtlMs) {
        response.writeHead(410).end();
        return;
      }
      grantConsumed = true;
      response.writeHead(200, {
        "Cache-Control": "no-store",
        "Content-Disposition": `attachment; filename="${FILE_NAME}"`,
        "Content-Length": String(FILE_CONTENT.byteLength),
        "Content-Type": "application/octet-stream",
        "Referrer-Policy": "no-referrer",
      });
      response.write(FILE_CONTENT.subarray(0, 16));
      response.end(FILE_CONTENT.subarray(16));
      return;
    }

    if (request.method === "POST" && url.pathname === "/verify") {
      void readJson(request)
        .then((body) => {
          const valid =
            body.size === FILE_CONTENT.byteLength &&
            body.sha256 ===
              createHash("sha256").update(FILE_CONTENT).digest("hex");
          if (!valid) {
            response
              .writeHead(422, { "Content-Type": "application/json" })
              .end('{"error":"checksum_mismatch"}');
            return;
          }
          verificationAcks += 1;
          response
            .writeHead(200, { "Content-Type": "application/json" })
            .end(JSON.stringify({ acknowledgements: verificationAcks }));
        })
        .catch(() => response.writeHead(400).end());
      return;
    }

    vite.middlewares(request, response, () => response.writeHead(404).end());
  });
  const interval = setInterval(() => {
    for (const stream of eventStreams) {
      stream.write(`event: heartbeat\ndata: ${Date.now()}\n\n`);
    }
  }, 50);

  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  if (!address || typeof address === "string") {
    throw new Error("Fixture server did not expose a TCP port");
  }
  const pageUrl = `http://127.0.0.1:${address.port}/`;

  return {
    fileName: FILE_NAME,
    fileSize: FILE_CONTENT.byteLength,
    pageUrl,
    sha256: createHash("sha256").update(FILE_CONTENT).digest("hex"),
    async close() {
      clearInterval(interval);
      for (const stream of eventStreams) stream.end();
      await new Promise<void>((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      );
      await vite.close();
    },
  };
}

function renderPage(grant: string, sha256: string): string {
  return `<!doctype html>
<html lang="en">
  <body data-expected-size="${FILE_CONTENT.byteLength}" data-expected-sha256="${sha256}">
    <p data-testid="session-state">connecting</p>
    <p data-testid="session-events">0</p>
    <a href="/download?grant=${grant}">Download fixture</a>
    <label>
      Verify downloaded file
      <input type="file" data-testid="verification-file">
    </label>
    <p data-testid="verification-state">idle</p>
    <p data-testid="verification-acks">0</p>
    <script>
      let eventCount = 0;
      const events = new EventSource('/events');
      events.addEventListener('ready', () => {
        document.querySelector('[data-testid=session-state]').textContent = 'connected';
      });
      events.addEventListener('heartbeat', () => {
        eventCount += 1;
        document.querySelector('[data-testid=session-events]').textContent =
          String(eventCount);
      });
      events.onerror = () => {
        document.querySelector('[data-testid=session-state]').textContent = 'disconnected';
      };
    </script>
    <script type="module" src="/integration/fixtures/file-transfer/main.ts"></script>
  </body>
</html>`;
}

async function readJson(
  request: IncomingMessage,
): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<
    string,
    unknown
  >;
}
