/** Sizes and speeds as the page shows them: 1024-based with one decimal. */
export function formatBytes(bytes: number): string {
  if (bytes >= 1024 ** 3) return (bytes / 1024 ** 3).toFixed(1) + " ГБ";
  if (bytes >= 1024 ** 2) return (bytes / 1024 ** 2).toFixed(1) + " МБ";
  if (bytes >= 1024) return (bytes / 1024).toFixed(1) + " КБ";
  return bytes + " Б";
}

/** The phone's file limit, named exactly in binary units. */
export function fileLimitMessage(bytes: number): string {
  const formatted = bytes >= 1024 ** 3
    ? `${bytes / 1024 ** 3} ГиБ`
    : bytes >= 1024 ** 2
      ? `${bytes / 1024 ** 2} МиБ`
      : bytes >= 1024
        ? `${bytes / 1024} КиБ`
        : `${bytes} Б`;
  return `Файл превышает установленный лимит ${formatted}.`;
}
