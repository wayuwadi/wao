/**
 * 屏蔽浏览器原生右键菜单，但保留 contextmenu 事件给业务菜单处理。
 */
export function installNativeContextMenuGuard(target: Document = document) {
  const preventNativeMenu = (event: MouseEvent) => event.preventDefault()

  target.addEventListener('contextmenu', preventNativeMenu, { capture: true })

  return () => target.removeEventListener('contextmenu', preventNativeMenu, { capture: true })
}
