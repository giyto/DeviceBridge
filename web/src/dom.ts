/** Finds an element the page markup must provide, or fails loudly naming the page area. */
export function required<T extends Element>(
  root: ParentNode,
  selector: string,
  area: string,
): T {
  const element = root.querySelector<T>(selector);
  if (element === null) throw new Error(`Missing ${area} element: ${selector}`);
  return element;
}
