import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import FileSettingsModal from '@/components/overlays/FileSettingsModal.vue'
const api = vi.hoisted(() => ({ fetchFileSettings: vi.fn(), saveFileSettings: vi.fn() }))
vi.mock('@/api/system', () => api)
enableAutoUnmount(afterEach)
describe('文件设置关闭保护', () => {
  afterEach(() => vi.clearAllMocks())
  it('非法输入和保存失败均不关闭，重试保存成功后关闭', async () => {
    api.fetchFileSettings.mockResolvedValue({ fileMaxMb: 50, imageMaxMb: 10, downloadCaptchaEnabled: true })
    const wrapper = mount(FileSettingsModal); await flushPromises()
    const input = wrapper.get('input[type="number"]')
    const escape = () => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }))
    await input.setValue(201); escape(); await flushPromises()
    expect(api.saveFileSettings).not.toHaveBeenCalled(); expect(wrapper.emitted('close')).toBeUndefined()
    await input.setValue(40); api.saveFileSettings.mockRejectedValueOnce(new Error('网络中断'))
    escape(); await flushPromises()
    expect(wrapper.text()).toContain('网络中断'); expect(wrapper.emitted('close')).toBeUndefined()
    expect((input.element as HTMLInputElement).value).toBe('40')
    api.saveFileSettings.mockResolvedValue({ fileMaxMb: 40, imageMaxMb: 10, downloadCaptchaEnabled: true })
    escape(); await flushPromises(); expect(wrapper.emitted('close')).toHaveLength(1)
  })
  it('接受 200MB 的普通文件上限', async () => {
    api.fetchFileSettings.mockResolvedValue({ fileMaxMb: 50, imageMaxMb: 10, downloadCaptchaEnabled: true })
    api.saveFileSettings.mockResolvedValue({ fileMaxMb: 200, imageMaxMb: 10, downloadCaptchaEnabled: true })
    const wrapper = mount(FileSettingsModal); await flushPromises()
    const input = wrapper.get('input[type="number"]')
    await input.setValue(200)
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(api.saveFileSettings).toHaveBeenCalledWith({ fileMaxMb: 200, imageMaxMb: 10, downloadCaptchaEnabled: true })
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
