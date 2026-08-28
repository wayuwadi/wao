import { describe, expect, it, vi } from 'vitest'

import { installNativeContextMenuGuard } from './nativeContextMenu'

describe('Agent 全局右键菜单守卫', () => {
  it('取消浏览器默认菜单且继续把事件交给业务处理', () => {
    const testDocument = document.implementation.createHTMLDocument('WAO Agent')
    const moduleButton = testDocument.createElement('button')
    const openBusinessMenu = vi.fn()
    moduleButton.addEventListener('contextmenu', openBusinessMenu)
    testDocument.body.append(moduleButton)

    const uninstall = installNativeContextMenuGuard(testDocument)
    const event = new MouseEvent('contextmenu', {
      bubbles: true,
      cancelable: true,
    })

    expect(moduleButton.dispatchEvent(event)).toBe(false)
    expect(event.defaultPrevented).toBe(true)
    expect(openBusinessMenu).toHaveBeenCalledOnce()

    uninstall()
  })
})
