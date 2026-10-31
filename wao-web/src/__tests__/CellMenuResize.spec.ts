import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import CellMenu from '@/components/task/CellMenu.vue'

enableAutoUnmount(afterEach)
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = '' })
describe('动态浮层边界', () => {
  it('候选或错误信息改变高度后重新定位，卸载时停止观察', async () => {
    let resized!: ResizeObserverCallback
    const observe = vi.fn(), disconnect = vi.fn()
    vi.stubGlobal('ResizeObserver', class {
      constructor(callback: ResizeObserverCallback) { resized = callback }
      observe = observe
      disconnect = disconnect
    })
    const anchor = document.createElement('button')
    document.body.append(anchor)
    anchor.getBoundingClientRect = () => ({ x: 20, y: 580, left: 20, right: 90, top: 580, bottom: 610, width: 70, height: 30, toJSON() {} })
    const wrapper = mount(CellMenu, { props: { anchor, minWidth: 300, customContent: true }, slots: { default: '<input aria-label="标签" />' } })
    await flushPromises()
    const menu = document.querySelector<HTMLElement>('.cell-menu')!
    Object.defineProperty(menu, 'offsetHeight', { configurable: true, value: 250 })
    resized([], {} as ResizeObserver)
    await flushPromises()
    expect(observe).toHaveBeenCalledWith(menu)
    expect(Number.parseFloat(menu.style.top) + 250).toBeLessThanOrEqual(window.innerHeight - 8)
    expect(menu.style.top).toBe('326px')
    wrapper.unmount()
    expect(disconnect).toHaveBeenCalledOnce()
  })
})
