import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import { useSaveBeforeRefresh } from '@/composables/saveBeforeRefresh'
import App from '@/App.vue'
const flush = vi.hoisted(() => vi.fn())
const toast = vi.hoisted(() => vi.fn())
vi.mock('@/stores/data', () => ({ useDataStore: () => ({ flushWorkspace: flush }) }))
vi.mock('@/composables/toast', () => ({ toast }))
beforeEach(() => { setActivePinia(createPinia()); flush.mockReset(); toast.mockReset() })
afterEach(() => vi.restoreAllMocks())
describe('刷新保存屏障', () => {
  it('注册的表单保存可被等待，组件卸载后移除', async () => {
    let resolve!: () => void
    const save = vi.fn(() => new Promise<void>(done => { resolve = done }))
    const component = defineComponent({ setup() { useSaveBeforeRefresh(save) }, template: '<div />' })
    const wrapper = mount(component)
    const requests: Promise<unknown>[] = []
    const event = () => new CustomEvent('wao-save-before-refresh', { detail: { waitUntil: (p: Promise<unknown>) => requests.push(p) } })
    window.dispatchEvent(event())
    expect(save).toHaveBeenCalledOnce()
    resolve()
    await Promise.all(requests)
    wrapper.unmount()
    window.dispatchEvent(event())
    expect(save).toHaveBeenCalledOnce()
  })
  it('任务保存失败保留页面，不发起后续刷新或表单保存', async () => {
    flush.mockResolvedValue(false)
    const listener = vi.fn()
    window.addEventListener('wao-save-before-refresh', listener)
    const wrapper = mount(App, { global: { stubs: { RouterView: true, ToastHost: true, VersionUpdateNotice: true } } })
    window.dispatchEvent(new Event('wao-workspace-reload'))
    await flushPromises()
    expect(flush).toHaveBeenCalledOnce()
    expect(listener).not.toHaveBeenCalled()
    expect(toast).toHaveBeenCalledWith(expect.stringContaining('保存失败'), 'ri-error-warning-line')
    wrapper.unmount()
    window.removeEventListener('wao-save-before-refresh', listener)
  })
})
