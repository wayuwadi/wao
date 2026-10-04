import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'
import { usePreferenceStore } from '@/stores/preferences'

const preferenceApi = vi.hoisted(() => ({
  fetchPreference: vi.fn<(key: string) => Promise<string>>(),
  savePreference: vi.fn<(key: string, value: string) => Promise<void>>(),
}))

vi.mock('@/api/favorites', () => preferenceApi)

describe('设置页成员偏好', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    preferenceApi.fetchPreference.mockResolvedValue('')
    preferenceApi.savePreference.mockResolvedValue(undefined)
  })

  it('旧偏好链接进入外观分类，小记提醒与 AI 设置移到独立分类', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/settings', component: { template: '<div />' } }],
    })
    await router.push('/settings?tab=prefs')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router] } })
    expect(wrapper.text()).not.toContain('音乐播放器')
    expect(wrapper.text()).not.toContain('显示播放器入口')
    expect(wrapper.find('.note-reminder-preferences').exists()).toBe(false)
    expect(wrapper.get('.snav-item[aria-current="page"]').text()).toBe('外观与陪伴')
    await wrapper.findAll('.snav-item').find(button => button.text() === '小记与 AI')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.note-reminder-preferences').exists()).toBe(true)
    expect(wrapper.find('.note-ai-defaults-preferences').exists()).toBe(true)
    expect(preferenceApi.fetchPreference).not.toHaveBeenCalledWith('music-player-settings-v1')
  })

  it('可切换极简与详细控件风格并跨设备保存', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/settings', component: { template: '<div />' } }],
    })
    await router.push('/settings?tab=prefs')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router] } })
    const choices = wrapper.findAll('.control-style-options button')
    expect(choices).toHaveLength(3)
    expect(choices[0]!.attributes('aria-checked')).toBe('true')

    await choices[2]!.trigger('click')
    await flushPromises()

    expect(preferenceApi.savePreference).toHaveBeenCalledWith('control-style-v1', 'detailed')
    expect(preferences.controlStyle).toBe('detailed')
    expect(choices[2]!.attributes('aria-checked')).toBe('true')
    expect(wrapper.find('.control-style-preferences').text()).toContain('已保存')
  })

  it('真实保存周起始日和庆祝动画且不再展示无效语言切换', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/settings', component: { template: '<div />' } }],
    })
    await router.push('/settings?tab=prefs')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router] } })
    const weekStart = wrapper.get<HTMLSelectElement>('select[aria-label="每周起始日"]')
    const celebration = wrapper.get<HTMLSelectElement>('select[aria-label="庆祝动画"]')

    await weekStart.setValue('周日')
    await flushPromises()
    await celebration.setValue('关闭')
    await flushPromises()

    expect(preferenceApi.savePreference).toHaveBeenCalledWith('week-start', '周日')
    expect(preferenceApi.savePreference).toHaveBeenCalledWith('celebration', '关闭')
    expect(preferences.weekStart).toBe('周日')
    expect(preferences.celebration).toBe('关闭')
    expect(wrapper.text()).not.toContain('界面显示语言')
    expect(wrapper.text()).toContain('当前仅提供完整的简体中文版本')
  })

  it('周起始日保存失败时恢复原值并可原位重试', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()
    preferenceApi.savePreference.mockRejectedValueOnce(new Error('网络不可用'))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/settings', component: { template: '<div />' } }],
    })
    await router.push('/settings?tab=prefs')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router] } })
    const weekStart = wrapper.get<HTMLSelectElement>('select[aria-label="每周起始日"]')
    await weekStart.setValue('周日')
    await flushPromises()

    expect(weekStart.element.value).toBe('周一')
    expect(wrapper.get('.personal-preference-save-error').text()).toContain('网络不可用')
    await wrapper.get('.personal-preference-save-error button').trigger('click')
    await flushPromises()

    expect(preferenceApi.savePreference).toHaveBeenLastCalledWith('week-start', '周日')
    expect(preferences.weekStart).toBe('周日')
  })

  it('界面风格保存失败后回退并提供重试', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()
    preferenceApi.savePreference.mockRejectedValueOnce(new Error('网络不可用'))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/settings', component: { template: '<div />' } }],
    })
    await router.push('/settings?tab=prefs')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router] } })
    await wrapper.findAll('.control-style-options button')[2]!.trigger('click')
    await flushPromises()

    expect(preferences.controlStyle).toBe('system')
    expect(wrapper.find('.control-style-save-error').text()).toContain('网络不可用')
    expect(wrapper.find('.control-style-save-error button').text()).toBe('重试保存')

    await wrapper.find('.control-style-save-error button').trigger('click')
    await flushPromises()
    expect(preferenceApi.savePreference).toHaveBeenLastCalledWith('control-style-v1', 'detailed')
    expect(preferences.controlStyle).toBe('detailed')
  })
})
