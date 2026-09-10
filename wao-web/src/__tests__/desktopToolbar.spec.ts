import { afterEach, describe, expect, it, vi } from 'vitest'
import { mountDesktopToolbar } from '@/utils/desktopToolbar'
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = '' })
describe('桌面顶栏只同步真实入口计数', () => {
  it('新壳同步未读变化并在卸载时禁用入口，旧壳不发导航', async () => {
    const assign = vi.fn()
    const realWindow = window
    vi.stubGlobal('window', { location: { assign }, __WAO_DESKTOP_TOOLBAR__: false })
    const root = document.createElement('div')
    root.innerHTML = '<span class="chat-badge">3</span><span class="notification-badge">99+</span>'
    document.body.append(root)
    mountDesktopToolbar(root)()
    expect(assign).not.toHaveBeenCalled()
    vi.stubGlobal('window', { location: { assign }, __WAO_DESKTOP_TOOLBAR__: true })
    const stop = mountDesktopToolbar(root)
    expect(assign).toHaveBeenLastCalledWith('/__desktop_toolbar__?ready=1&chat=3&notifications=99%2B')
    root.querySelector('.chat-badge')!.remove()
    await new Promise(resolve => realWindow.setTimeout(resolve, 0))
    expect(assign).toHaveBeenLastCalledWith('/__desktop_toolbar__?ready=1&chat=0&notifications=99%2B')
    stop()
    expect(assign).toHaveBeenLastCalledWith('/__desktop_toolbar__?ready=0')
  })
})
