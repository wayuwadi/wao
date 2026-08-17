import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'
import { usePreferenceStore } from '@/stores/preferences'

const preferenceApi = vi.hoisted(() => ({
  fetchPreference: vi.fn(),
  savePreference: vi.fn(),
}))

vi.mock('@/api/favorites', () => preferenceApi)

describe('设置页音乐播放器偏好', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    preferenceApi.fetchPreference.mockResolvedValue('')
    preferenceApi.savePreference.mockResolvedValue(undefined)
  })

  it('保存默认开合和入口显隐到当前成员偏好', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/settings', component: { template: '<div />' } }],
    })
    await router.push('/settings?tab=prefs')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router] } })
    const toggles = wrapper.findAll('.music-preferences .toggle')
    expect(toggles).toHaveLength(2)
    expect(toggles[0]!.attributes('aria-pressed')).toBe('false')
    expect(toggles[1]!.attributes('aria-pressed')).toBe('true')

    await toggles[0]!.trigger('click')
    await toggles[1]!.trigger('click')
    await wrapper.get('.music-preference-actions .btn-primary').trigger('click')
    await flushPromises()

    expect(preferenceApi.savePreference).toHaveBeenCalledWith(
      'music-player-settings-v1',
      JSON.stringify({ defaultOpen: true, launcherVisible: false }),
    )
    expect(preferences.musicPlayerDefaultOpen).toBe(true)
    expect(preferences.musicPlayerLauncherVisible).toBe(false)
  })
})
