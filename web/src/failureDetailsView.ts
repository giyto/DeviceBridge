import type { FailureDescriptor } from "./failureCatalog";

const allowedDetailKeys = new Set([
  "protocolVersion",
  "operationId",
  "direction",
  "sizeCategory",
  "lifecycleState",
  "failureCode",
]);

export function formatFailureTechnicalDetails(
  failure: FailureDescriptor,
  untrustedDetails: Readonly<Record<string, string>> = {},
): string {
  const lines = [
    `Код: ${failure.code}`,
    `Состояние: ${failure.severity}`,
    `Действия: ${[...failure.recoveryActions].sort().join(", ")}`,
  ];
  for (const [key, rawValue] of Object.entries(untrustedDetails).sort(([left], [right]) =>
    left.localeCompare(right))) {
    if (!allowedDetailKeys.has(key)) continue;
    lines.push(`${key}: ${rawValue.trim().slice(0, 64)}`);
  }
  return lines.join("\n");
}

export function createFailureDetailsView(
  failure: FailureDescriptor,
  untrustedDetails: Readonly<Record<string, string>>,
  copyText: (value: string) => void,
  ownerDocument: Document = document,
): HTMLDetailsElement {
  const detailsText = formatFailureTechnicalDetails(failure, untrustedDetails);
  const root = ownerDocument.createElement("details");
  root.className = "failure-details";

  const summary = ownerDocument.createElement("summary");
  summary.textContent = "Технические сведения";
  root.append(summary);
  root.addEventListener("toggle", () => {
    if (!root.open && root.contains(ownerDocument.activeElement)) summary.focus();
  });

  const content = ownerDocument.createElement("pre");
  content.className = "failure-details__content";
  content.textContent = detailsText;
  root.append(content);

  const copyButton = ownerDocument.createElement("button");
  copyButton.type = "button";
  copyButton.className = "failure-details__copy";
  copyButton.textContent = "Скопировать сведения";
  copyButton.addEventListener("click", () => copyText(detailsText));
  root.append(copyButton);

  return root;
}
