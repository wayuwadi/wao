import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  hasDesktopToolbar,
  mountDesktopToolbar,
  syncDesktopGlobalShortcuts,
} from '@/utils/desktopToolbar'
afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
describe('桌面顶栏只同步真实入口计数', () => {
  it('旧壳保留网页入口且不再导航，新壳用标题消息同步', async () => {
    const assign = vi.fn<(url: string) => void>()
    const realWindow = window
    vi.stubGlobal('window', { location: { assign }, __WAO_DESKTOP_TOOLBAR__: false })
    const root = document.createElement('div')
    root.innerHTML =
      '<span class="context-nav"><span>首页</span><select><option disabled>切换</option><option>今日</option></select></span><button data-desktop-action="theme" data-theme="light"></button><button data-desktop-menu-index="0">更新日志</button><span class="chat-badge">3</span><span class="notification-badge">99+</span>'
    document.body.append(root)
    mountDesktopToolbar(root)()
    expect(assign).not.toHaveBeenCalled()
    expect(hasDesktopToolbar()).toBe(false)

    vi.stubGlobal('window', { location: { assign }, __WAO_DESKTOP_TOOLBAR__: true })
    mountDesktopToolbar(root)()
    syncDesktopGlobalShortcuts({ 'new-note': 'Alt+Shift+Y' })
    expect(hasDesktopToolbar()).toBe(false)
    expect(assign).not.toHaveBeenCalled()

    vi.stubGlobal('window', {
      location: { assign },
      __WAO_DESKTOP_TOOLBAR__: true,
      __WAO_DESKTOP_BRIDGE__: 2,
    })
    expect(hasDesktopToolbar()).toBe(true)
    const stop = mountDesktopToolbar(root)
    expect(document.title).toBe(
      'WAO_DESKTOP_SIGNAL:/__desktop_toolbar__?ready=1&theme=light&chat=3&notifications=99%2B&context=%E9%A6%96%E9%A1%B5&contextItems=%5B%22%E4%BB%8A%E6%97%A5%22%5D&menus=%5B%22%E6%9B%B4%E6%96%B0%E6%97%A5%E5%BF%97%22%5D',
    )
    expect(assign).not.toHaveBeenCalled()
    root.querySelector('.chat-badge')!.remove()
    await new Promise((resolve) => realWindow.setTimeout(resolve, 0))
    expect(document.title).toBe(
      'WAO_DESKTOP_SIGNAL:/__desktop_toolbar__?ready=1&theme=light&chat=0&notifications=99%2B&context=%E9%A6%96%E9%A1%B5&contextItems=%5B%22%E4%BB%8A%E6%97%A5%22%5D&menus=%5B%22%E6%9B%B4%E6%96%B0%E6%97%A5%E5%BF%97%22%5D',
    )
    root.querySelector('[data-desktop-action=theme]')!.setAttribute('data-theme', 'dark')
    await new Promise((resolve) => realWindow.setTimeout(resolve, 0))
    expect(document.title).toBe(
      'WAO_DESKTOP_SIGNAL:/__desktop_toolbar__?ready=1&theme=dark&chat=0&notifications=99%2B&context=%E9%A6%96%E9%A1%B5&contextItems=%5B%22%E4%BB%8A%E6%97%A5%22%5D&menus=%5B%22%E6%9B%B4%E6%96%B0%E6%97%A5%E5%BF%97%22%5D',
    )
    stop()
    expect(document.title).toBe('WAO_DESKTOP_SIGNAL:/__desktop_toolbar__?ready=0')
    expect(assign).not.toHaveBeenCalled()
    syncDesktopGlobalShortcuts({ 'new-note': 'Alt+Shift+Y' })
    expect(document.title).toBe(
      'WAO_DESKTOP_SIGNAL:/__desktop_shortcuts__?new-note=Alt%2BShift%2BY',
    )
    expect(assign).not.toHaveBeenCalled()

  })
})
