/** Versioned UI-only bridge. Older clients retain their in-page buttons. */
export function hasDesktopToolbar(): boolean {
  return (window as Window & { __WAO_DESKTOP_TOOLBAR__?: boolean }).__WAO_DESKTOP_TOOLBAR__ === true
}

const DESKTOP_SIGNAL_PREFIX = 'WAO_DESKTOP_SIGNAL:'

/**
 * 新壳通过文档标题变化读取受限状态，避免把每次角标/菜单变化伪装成顶层导航。
 * 旧壳继续使用原导航拦截器，直到用户完成客户端升级。
 */
function sendDesktopSignal(path: string): void {
  const bridge = (window as Window & { __WAO_DESKTOP_BRIDGE__?: number }).__WAO_DESKTOP_BRIDGE__
  if (bridge && bridge >= 2) {
    document.title = `${DESKTOP_SIGNAL_PREFIX}${path}`
    return
  }
  window.location.assign(path)
}

export function mountDesktopToolbar(root: HTMLElement): () => void {
  if (!hasDesktopToolbar()) return () => {}
  let previous = ''
  const publish = () => {
    const contextItems = [
      ...root.querySelectorAll<HTMLOptionElement>('.context-nav select option:not(:disabled)'),
    ]
      .slice(0, 50)
      .map((item) => item.textContent?.trim().slice(0, 80) || '')
      .filter(Boolean)
    const menus = [...root.querySelectorAll<HTMLElement>('[data-desktop-menu-index]')]
      .slice(0, 50)
      .map((item) => item.textContent?.trim().slice(0, 80) || '')
      .filter(Boolean)
    const query = new URLSearchParams({
      ready: '1',
      theme:
        root.querySelector('[data-desktop-action=theme]')?.getAttribute('data-theme') === 'dark'
          ? 'dark'
          : 'light',
      chat: root.querySelector('.chat-badge')?.textContent?.trim() || '0',
      notifications: root.querySelector('.notification-badge')?.textContent?.trim() || '0',
      context:
        root.querySelector<HTMLElement>('.context-nav > span')?.textContent?.trim().slice(0, 80) ||
        '',
      contextItems: JSON.stringify(contextItems),
      menus: JSON.stringify(menus),
    }).toString()
    if (previous === query) return
    previous = query
    sendDesktopSignal(`/__desktop_toolbar__?${query}`)
  }
  const observer = new MutationObserver(publish)
  observer.observe(root, {
    childList: true,
    subtree: true,
    characterData: true,
    attributes: true,
    attributeFilter: ['data-theme'],
  })
  publish()
  return () => {
    observer.disconnect()
    sendDesktopSignal('/__desktop_toolbar__?ready=0')
  }
}

/** Send member-owned global shortcut settings to the trusted native shell bridge. */
export function syncDesktopGlobalShortcuts(config: Record<string, string>): void {
  if (!hasDesktopToolbar()) return
  const query = new URLSearchParams()
  for (const action of ['new-task', 'new-note', 'chat']) {
    const shortcut = config[action]
    if (shortcut) query.set(action, shortcut)
  }
  sendDesktopSignal(`/__desktop_shortcuts__?${query}`)
}
