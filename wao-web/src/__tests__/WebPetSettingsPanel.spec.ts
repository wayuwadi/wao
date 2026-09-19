import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import WebPetSettingsPanel from '@/components/settings/WebPetSettingsPanel.vue'
import { useWebPetStore } from '@/stores/webPet'
import { fetchPreference, savePreference } from '@/api/favorites'

vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ userId: 'pet-settings-member' }) }))

vi.mock('@/api/favorites', () => ({
  fetchPreference: vi.fn<() => Promise<string>>(),
  savePreference: vi.fn<() => Promise<void>>(),
}))

describe('网页宠物设置', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    localStorage.clear()
    vi.mocked(fetchPreference).mockResolvedValue(JSON.stringify({
      hoverAction: 'QUICK_ACTIONS', leftClickAction: 'QUICK_ACTIONS', rightClickAction: 'SETTINGS',
      noticeSnoozeMinutes: 5, petSnoozeMinutes: 10, noticeRotationSeconds: 7,
      showTaskNotices: true, idleAnimation: true,
    }))
    vi.mocked(savePreference).mockResolvedValue(undefined)
  })

  it('读取当前成员设置并保存一次性提醒开关', async () => {
    const wrapper = mount(WebPetSettingsPanel)
    await flushPromises()

    expect(fetchPreference).toHaveBeenCalledWith('web-pet-settings-v1')
    expect(wrapper.find('select').exists()).toBe(false)
    await wrapper.findAll('input[type=checkbox]')[0]!.setValue(false)
    await wrapper.get('button.primary').trigger('click')
    await flushPromises()

    const [key, raw] = vi.mocked(savePreference).mock.calls[0]!
    expect(key).toBe('web-pet-settings-v1')
    expect(JSON.parse(raw)).toMatchObject({
      showTaskNotices: false,
    })
    expect(wrapper.get('button.primary').text()).toBe('已保存')
    wrapper.unmount()
  })

  it('保存失败时保留表单草稿并提供重试入口', async () => {
    vi.mocked(savePreference).mockRejectedValueOnce(new Error('偏好服务暂时不可用'))
    const wrapper = mount(WebPetSettingsPanel)
    await flushPromises()
    await wrapper.findAll('input[type=checkbox]')[0]!.setValue(false)
    await wrapper.get('button.primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('偏好服务暂时不可用')
    expect((wrapper.findAll('input[type=checkbox]')[0]!.element as HTMLInputElement).checked).toBe(false)
    expect(wrapper.text()).toContain('重试保存')
    wrapper.unmount()
  })

  it('读取失败不把默认值伪装成已保存配置并允许重试', async () => {
    vi.mocked(fetchPreference).mockRejectedValueOnce(new Error('偏好读取失败'))
    const wrapper = mount(WebPetSettingsPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('偏好读取失败')
    expect(wrapper.text()).toContain('重新读取')
    expect(wrapper.find('.pet-settings').exists()).toBe(false)
    wrapper.unmount()
  })
  it('保存期间继续编辑不被旧响应覆盖，后续修改仍需保存', async () => {
    let finish!: () => void
    vi.mocked(savePreference).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(WebPetSettingsPanel)
    await flushPromises()
    const boxes = wrapper.findAll('input[type=checkbox]')
    await boxes[0]!.setValue(false)
    await wrapper.get('button.primary').trigger('click')
    await boxes[1]!.setValue(false)
    finish()
    await flushPromises()
    expect((boxes[1]!.element as HTMLInputElement).checked).toBe(false)
    expect(wrapper.get('button.primary').text()).toBe('保存宠物设置')
    expect(useWebPetStore().settings.idleAnimation).toBe(true)
    await wrapper.get('button.primary').trigger('click')
    await flushPromises()
    expect(useWebPetStore().settings.idleAnimation).toBe(false)
    expect(wrapper.get('button.primary').text()).toBe('已保存')
    wrapper.unmount()
  })

  it('保存期间改回原值也必须保留，不能误报已保存', async () => {
    let finish!: () => void
    vi.mocked(savePreference).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(WebPetSettingsPanel)
    await flushPromises()
    const checkbox = wrapper.findAll('input[type=checkbox]')[0]!
    await checkbox.setValue(false)
    await wrapper.get('button.primary').trigger('click')
    await checkbox.setValue(true)
    expect(JSON.parse(localStorage.getItem('wao-web-pet-settings-draft-v1:pet-settings-member')!).showTaskNotices).toBe(true)
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    finish()
    await flushPromises()
    expect((checkbox.element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.get('button.primary').text()).toBe('保存宠物设置')
    wrapper.unmount()
  })

  it('与宠物共享慢读取时不提前显示默认表单', async () => {
    let finish!: (raw: string) => void
    vi.mocked(fetchPreference).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    void useWebPetStore().load()
    const wrapper = mount(WebPetSettingsPanel)
    await flushPromises()
    expect(wrapper.find('.pet-settings').exists()).toBe(false)
    expect(wrapper.text()).toContain('正在读取宠物设置')
    finish(JSON.stringify({ idleAnimation: false }))
    await flushPromises()
    expect(fetchPreference).toHaveBeenCalledOnce()
    expect((wrapper.findAll('input[type=checkbox]')[1]!.element as HTMLInputElement).checked).toBe(false)
    wrapper.unmount()
  })

  it('离开后重新进入恢复成员草稿，保存后清除', async () => {
    const wrapper = mount(WebPetSettingsPanel)
    await flushPromises()
    await wrapper.findAll('input[type=checkbox]')[0]!.setValue(false)
    wrapper.unmount()
    const restored = mount(WebPetSettingsPanel)
    await flushPromises()
    expect(restored.text()).toContain('已恢复未保存的宠物设置')
    expect((restored.findAll('input[type=checkbox]')[0]!.element as HTMLInputElement).checked).toBe(false)
    await restored.get('button.primary').trigger('click')
    await flushPromises()
    expect(localStorage.getItem('wao-web-pet-settings-draft-v1:pet-settings-member')).toBeNull()
    restored.unmount()
  })

  it('刷新保存屏障等待在途保存并提交期间新增修改', async () => {
    let finish!: () => void
    vi.mocked(savePreference).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(WebPetSettingsPanel)
    await flushPromises()
    const boxes = wrapper.findAll('input[type=checkbox]')
    await boxes[0]!.setValue(false)
    await wrapper.get('button.primary').trigger('click')
    await boxes[1]!.setValue(false)
    const pending: Promise<unknown>[] = []
    window.dispatchEvent(new CustomEvent('wao-save-before-refresh', { detail: { waitUntil: (p: Promise<unknown>) => pending.push(p) } }))
    expect(savePreference).toHaveBeenCalledTimes(1)
    finish()
    await Promise.all(pending)
    await flushPromises()
    expect(savePreference).toHaveBeenCalledTimes(2)
    expect(useWebPetStore().settings).toMatchObject({ showTaskNotices: false, idleAnimation: false })
    expect(wrapper.get('button.primary').text()).toBe('已保存')
    wrapper.unmount()
  })

})
