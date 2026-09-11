/** Versioned UI-only bridge. Older clients retain their in-page buttons. */
export function hasDesktopToolbar(): boolean {
  return (window as Window & { __WAO_DESKTOP_TOOLBAR__?: boolean }).__WAO_DESKTOP_TOOLBAR__ === true
}

export function mountDesktopToolbar(root: HTMLElement): () => void {
  if (!hasDesktopToolbar()) return () => {}
  let previous = ''
  const publish = () => {
    const query = new URLSearchParams({
      ready: '1',
      theme: root.querySelector('[data-desktop-action=theme]')?.getAttribute('data-theme') === 'dark' ? 'dark' : 'light',
      chat: root.querySelector('.chat-badge')?.textContent?.trim() || '0',
      notifications: root.querySelector('.notification-badge')?.textContent?.trim() || '0',
    }).toString()
    if (previous === query) return
    previous = query
    window.location.assign(`/__desktop_toolbar__?${query}`)
  }
  const observer = new MutationObserver(publish)
  observer.observe(root, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['data-theme'] })
  publish()
  return () => {
    observer.disconnect()
    window.location.assign('/__desktop_toolbar__?ready=0')
  }
}
