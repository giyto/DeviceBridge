import { existsSync, lstatSync, readFileSync, readdirSync } from "node:fs";
import { extname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const TEXT_EXTENSIONS = new Set([
  ".css", ".html", ".java", ".js", ".json", ".kt", ".kts",
  ".mjs", ".properties", ".toml", ".ts", ".tsx", ".xml",
]);
const SKIPPED_DIRECTORIES = new Set([
  ".git", ".gradle", "androidTest", "build", "debug", "node_modules",
  "test", "tests",
]);

const RULES = [
  {
    rule: "PRIVATE_KEY",
    pattern: /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/g,
  },
  {
    rule: "SECRET_LITERAL",
    pattern:
      /\b(?:password|token|secret|apiKey|privateKey)\s*[:=]\s*["'][A-Za-z0-9_+/=.-]{12,}["']/gi,
  },
  {
    rule: "ANALYTICS_OR_CDN",
    pattern:
      /\b(?:google-analytics|firebase-analytics|mixpanel|segment\.io|sentry\.io|cdn\.jsdelivr\.net|unpkg\.com|cdnjs\.cloudflare\.com|fonts\.googleapis\.com|fonts\.gstatic\.com)\b/gi,
  },
  {
    rule: "SERVICE_WORKER",
    pattern: /\b(?:navigator\.)?serviceWorker\s*\.\s*register\s*\(/g,
  },
  {
    rule: "ABSOLUTE_LOCAL_PATH",
    pattern:
      /(?:\b[A-Za-z]:[\\/](?:Users|Documents and Settings)[\\/][^\s"'<>]+|\/(?:Users|home)\/[^\s"'<>]+)/g,
  },
  {
    rule: "SENSITIVE_LOG",
    pattern:
      /\b(?:console\.(?:log|debug|info|warn|error)|Log\.[vdiew]|println)\s*\([^\n]*(?:token|password|pairingCode|payload|content)/gi,
  },
];
const URL_PATTERN = /https?:\/\/[^\s"'<>)}\]]+/g;

export function scanText(text, displayPath) {
  const findings = [];
  for (const { rule, pattern } of RULES) {
    for (const match of text.matchAll(pattern)) {
      findings.push(finding(rule, displayPath, text, match.index ?? 0));
    }
  }
  for (const match of text.matchAll(URL_PATTERN)) {
    if (!isCommentLine(text, match.index ?? 0) && !isAllowedUrl(match[0])) {
      findings.push(finding("EXTERNAL_URL", displayPath, text, match.index ?? 0));
    }
  }
  return findings.sort((left, right) =>
    left.line - right.line || left.rule.localeCompare(right.rule)
  );
}

function finding(rule, path, text, index) {
  return {
    rule,
    path,
    line: text.slice(0, index).split(/\r?\n/).length,
  };
}

function isCommentLine(text, index) {
  const lineStart = text.lastIndexOf("\n", index) + 1;
  const prefix = text.slice(lineStart, index).trimStart();
  return prefix.startsWith("#") ||
    prefix.startsWith("//") ||
    prefix.startsWith("*") ||
    prefix.startsWith("<!--");
}

function isAllowedUrl(rawUrl) {
  if (rawUrl.includes("$") || rawUrl.includes("{")) return true;
  let url;
  try {
    url = new URL(rawUrl);
  } catch {
    return false;
  }
  const host = url.hostname.toLowerCase();
  if (
    host === "schemas.android.com" ||
    host === "example.com" ||
    host === "localhost" ||
    host === "0.0.0.0" ||
    host === "127.0.0.1" ||
    host === "::1"
  ) {
    return true;
  }
  if (/^10\./.test(host) || /^192\.168\./.test(host)) return true;
  const private172 = /^172\.(\d+)\./.exec(host);
  return private172 !== null &&
    Number(private172[1]) >= 16 &&
    Number(private172[1]) <= 31;
}

function collectFiles(inputPath) {
  const absolute = resolve(inputPath);
  if (!existsSync(absolute)) {
    throw new Error("Release scan input does not exist: " + inputPath);
  }
  const stat = lstatSync(absolute);
  if (stat.isSymbolicLink()) return [];
  if (!stat.isDirectory()) {
    return TEXT_EXTENSIONS.has(extname(absolute)) ? [absolute] : [];
  }
  return readdirSync(absolute, { withFileTypes: true })
    .filter((entry) => !SKIPPED_DIRECTORIES.has(entry.name))
    .flatMap((entry) => collectFiles(resolve(absolute, entry.name)));
}

function runCli(inputPaths) {
  if (inputPaths.length === 0) {
    throw new Error("Provide at least one release source or artifact path.");
  }
  const findings = inputPaths
    .flatMap(collectFiles)
    .sort()
    .flatMap((path) => scanText(readFileSync(path, "utf8"), path));
  if (findings.length > 0) {
    for (const item of findings) {
      console.error(item.rule + " " + item.path + ":" + item.line);
    }
    process.exitCode = 1;
    return;
  }
  console.log("Release policy scan passed (" + inputPaths.length + " roots).");
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  runCli(process.argv.slice(2));
}
