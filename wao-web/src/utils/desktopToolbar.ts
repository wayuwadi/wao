/** Versioned UI-only bridge. Older clients retain in-page context and use their fixed native actions. */
export function hasDesktopToolbar(): boolean {
  const shell = window as Window & {
    __WAO_DESKTOP_TOOLBAR__?: boolean
    __WAO_DESKTOP_BRIDGE__?: number
  }
  return shell.__WAO_DESKTOP_TOOLBAR__ === true && (shell.__WAO_DESKTOP_BRIDGE__ ?? 0) >= 2
}

export function hasLegacyDesktopToolbar(): boolean {
  const shell = window as Window & {
    __WAO_DESKTOP_TOOLBAR__?: boolean
    __WAO_DESKTOP_BRIDGE__?: number
  }
  return shell.__WAO_DESKTOP_TOOLBAR__ === true && (shell.__WAO_DESKTOP_BRIDGE__ ?? 0) < 2
}

const DESKTOP_SIGNAL_PREFIX = 'WAO_DESKTOP_SIGNAL:'

/**
 * 新壳通过文档标题变化读取受限状态，避免把每次角标/菜单变化伪装成顶层导航。
 * 旧壳只在挂载后发送一次 ready 信号，恢复固定原生按钮但不持续同步易变状态。
 */
function sendDesktopSignal(path: string): void {
  document.title = `${DESKTOP_SIGNAL_PREFIX}${path}`
}

export function mountDesktopToolbar(root: HTMLElement): () => void {
  if (!hasDesktopToolbar()) {
    if (!hasLegacyDesktopToolbar()) return () => {}
    const timer = globalThis.setTimeout(() => {
      window.location.assign('/__desktop_toolbar__?ready=1')
    }, 0)
    return () => globalThis.clearTimeout(timer)
  }
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
