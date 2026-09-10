/** A UI marker injected by the local shell; it grants no native permissions. */
export function isDesktopWorkspace(): boolean {
  return (window as Window & { __WAO_DESKTOP__?: boolean }).__WAO_DESKTOP__ === true
}
