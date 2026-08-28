import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import WebPetSettingsPanel from '@/components/settings/WebPetSettingsPanel.vue'
import { fetchPreference, savePreference } from '@/api/favorites'

vi.mock('@/api/favorites', () => ({
  fetchPreference: vi.fn<() => Promise<string>>(),
  savePreference: vi.fn<() => Promise<void>>(),
}))

describe('网页宠物设置', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(fetchPreference).mockResolvedValue(JSON.stringify({
      hoverAction: 'QUICK_ACTIONS', leftClickAction: 'QUICK_ACTIONS', rightClickAction: 'SETTINGS',
      noticeSnoozeMinutes: 5, petSnoozeMinutes: 10, noticeRotationSeconds: 7,
      showTaskNotices: true, idleAnimation: true,
    }))
    vi.mocked(savePreference).mockResolvedValue(undefined)
  })

  it('读取当前成员设置并保存三种触发方式与自选静默时长', async () => {
    const wrapper = mount(WebPetSettingsPanel)
    await flushPromises()

    expect(fetchPreference).toHaveBeenCalledWith('web-pet-settings-v1')
    await wrapper.get('select[aria-label="宠物悬停动作"]').setValue('NONE')
    await wrapper.get('select[aria-label="宠物右键动作"]').setValue('QUICK_ACTIONS')
    await wrapper.findAll('input[type="number"]')[0]!.setValue(15)
    await wrapper.get('button.primary').trigger('click')
    await flushPromises()

    const [key, raw] = vi.mocked(savePreference).mock.calls[0]!
    expect(key).toBe('web-pet-settings-v1')
    expect(JSON.parse(raw)).toMatchObject({
      hoverAction: 'NONE', rightClickAction: 'QUICK_ACTIONS', noticeSnoozeMinutes: 15,
    })
    expect(wrapper.get('button.primary').text()).toBe('已保存')
  })

  it('保存失败时保留表单草稿并提供重试入口', async () => {
    vi.mocked(savePreference).mockRejectedValueOnce(new Error('偏好服务暂时不可用'))
    const wrapper = mount(WebPetSettingsPanel)
    await flushPromises()
    await wrapper.get('select[aria-label="宠物左键动作"]').setValue('NONE')
    await wrapper.get('button.primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('偏好服务暂时不可用')
    expect((wrapper.get('select[aria-label="宠物左键动作"]').element as HTMLSelectElement).value).toBe('NONE')
    expect(wrapper.text()).toContain('重试保存')
  })

  it('读取失败不把默认值伪装成已保存配置并允许重试', async () => {
    vi.mocked(fetchPreference).mockRejectedValueOnce(new Error('偏好读取失败'))
    const wrapper = mount(WebPetSettingsPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('偏好读取失败')
    expect(wrapper.text()).toContain('重新读取')
    expect(wrapper.find('.pet-settings').exists()).toBe(false)
  })
})
