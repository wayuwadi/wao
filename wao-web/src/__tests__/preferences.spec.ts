import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePreferenceStore } from '@/stores/preferences'

const preferenceApi = vi.hoisted(() => ({
  fetchPreference: vi.fn<(key: string) => Promise<string>>(),
  savePreference: vi.fn<(key: string, value: string) => Promise<void>>(),
}))

vi.mock('@/api/favorites', () => preferenceApi)

describe('成员级界面偏好', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    preferenceApi.fetchPreference.mockResolvedValue('')
    preferenceApi.savePreference.mockResolvedValue(undefined)
  })

  it('未配置时默认不展开播放器但显示入口', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()

    expect(preferences.musicPlayerDefaultOpen).toBe(false)
    expect(preferences.musicPlayerLauncherVisible).toBe(true)
    expect(preferences.controlStyle).toBe('system')
    expect(preferences.effectiveControlStyle).toBe('minimal')
    expect(preferences.compactControls).toBe(true)
    expect(preferenceApi.fetchPreference).toHaveBeenCalledWith('music-player-settings-v1')
    expect(preferenceApi.fetchPreference).toHaveBeenCalledWith('control-style-v1')
  })

  it('读取并保存跨设备控件风格', async () => {
    preferenceApi.fetchPreference.mockImplementation((key: string) => Promise.resolve(
      key === 'control-style-v1' ? 'detailed' : '',
    ))
    const preferences = usePreferenceStore()
    await preferences.load()

    expect(preferences.controlStyle).toBe('detailed')
    expect(preferences.compactControls).toBe(false)

    await preferences.setControlStyle('minimal')
    expect(preferenceApi.savePreference).toHaveBeenCalledWith('control-style-v1', 'minimal')
    expect(preferences.controlStyle).toBe('minimal')
    expect(preferences.compactControls).toBe(true)
  })

  it('控件风格保存失败时不伪装已生效', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()
    preferenceApi.savePreference.mockRejectedValueOnce(new Error('网络不可用'))

    await expect(preferences.setControlStyle('detailed')).rejects.toThrow('网络不可用')
    expect(preferences.controlStyle).toBe('system')
    expect(preferences.compactControls).toBe(true)
  })

  it('从服务端读取成员设置并原子保存两个开关', async () => {
    preferenceApi.fetchPreference.mockImplementation((key: string) => Promise.resolve(
      key === 'music-player-settings-v1'
        ? JSON.stringify({ defaultOpen: true, launcherVisible: false })
        : '',
    ))
    const preferences = usePreferenceStore()
    await preferences.load()

    expect(preferences.musicPlayerDefaultOpen).toBe(true)
    expect(preferences.musicPlayerLauncherVisible).toBe(false)

    await preferences.setMusicPlayerSettings({ defaultOpen: false, launcherVisible: true })
    expect(preferenceApi.savePreference).toHaveBeenCalledWith(
      'music-player-settings-v1',
      JSON.stringify({ defaultOpen: false, launcherVisible: true }),
    )
  })

  it('保存失败时不伪装已生效', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()
    preferenceApi.savePreference.mockRejectedValueOnce(new Error('网络不可用'))

    await expect(preferences.setMusicPlayerSettings({ defaultOpen: true, launcherVisible: false }))
      .rejects.toThrow('网络不可用')
    expect(preferences.musicPlayerDefaultOpen).toBe(false)
    expect(preferences.musicPlayerLauncherVisible).toBe(true)
    expect(preferences.controlStyle).toBe('system')
  })

  it('切换登录成员时先清除上一人的播放器偏好', async () => {
    preferenceApi.fetchPreference.mockImplementation((key: string) => Promise.resolve(
      key === 'music-player-settings-v1'
        ? JSON.stringify({ defaultOpen: true, launcherVisible: false })
        : '',
    ))
    const preferences = usePreferenceStore()
    preferences.prepareForMember('member-a')
    await preferences.load()
    expect(preferences.musicPlayerDefaultOpen).toBe(true)

    preferences.prepareForMember('member-b')
    expect(preferences.loaded).toBe(false)
    expect(preferences.musicPlayerDefaultOpen).toBe(false)
    expect(preferences.musicPlayerLauncherVisible).toBe(true)
  })

  it('上一名成员的迟到响应不会覆盖新成员偏好', async () => {
    let resolveFirstMusic!: (value: string) => void
    let servingFirstMember = true
    preferenceApi.fetchPreference.mockImplementation((key: string) => {
      if (key !== 'music-player-settings-v1') return Promise.resolve('')
      if (servingFirstMember) return new Promise<string>((resolve) => { resolveFirstMusic = resolve })
      return Promise.resolve(JSON.stringify({ defaultOpen: false, launcherVisible: true }))
    })
    const preferences = usePreferenceStore()
    preferences.prepareForMember('member-a')
    const firstLoad = preferences.load()

    servingFirstMember = false
    preferences.prepareForMember('member-b')
    await preferences.load()
    resolveFirstMusic(JSON.stringify({ defaultOpen: true, launcherVisible: false }))
    await firstLoad

    expect(preferences.musicPlayerDefaultOpen).toBe(false)
    expect(preferences.musicPlayerLauncherVisible).toBe(true)
  })
})
