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

  it('小记偏好提供默认值，并按成员保存提醒和 AI 默认能力', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()

    expect(preferences.noteFullscreenReminder).toEqual({ enabled: true, delayMinutes: 1 })
    expect(preferences.noteAiDefaults).toEqual({ title: true, polish: true, image: true, executionMode: 'parallel', maxConcurrent: 3 })
    await preferences.setNoteFullscreenReminder({ enabled: false, delayMinutes: 5 })
    expect(preferenceApi.savePreference).toHaveBeenCalledWith(
      'note-fullscreen-reminder-v1', JSON.stringify({ enabled: false, delayMinutes: 5 }),
    )
    expect(preferences.noteFullscreenReminder).toEqual({ enabled: false, delayMinutes: 5 })
    await preferences.setNoteAiDefaults({ title: false, polish: true, image: false, executionMode: 'serial', maxConcurrent: 4 })
    expect(preferenceApi.savePreference).toHaveBeenCalledWith(
      'note-ai-defaults-v1', JSON.stringify({ title: false, polish: true, image: false, executionMode: 'serial', maxConcurrent: 4 }),
    )
    expect(preferences.noteAiDefaults).toEqual({ title: false, polish: true, image: false, executionMode: 'serial', maxConcurrent: 4 })

    preferences.prepareForMember('another-member')
    expect(preferences.noteFullscreenReminder).toEqual({ enabled: true, delayMinutes: 1 })
    expect(preferences.noteAiDefaults).toEqual({ title: true, polish: true, image: true, executionMode: 'parallel', maxConcurrent: 3 })
  })

  it('读取旧小记偏好时补齐并行默认值，且保留有效并发设置', async () => {
    preferenceApi.fetchPreference.mockImplementation((key: string) => Promise.resolve(
      key === 'note-ai-defaults-v1'
        ? JSON.stringify({ title: true, polish: false, image: true, executionMode: 'serial', maxConcurrent: 5 })
        : '',
    ))
    const preferences = usePreferenceStore()
    await preferences.load()
    expect(preferences.noteAiDefaults).toEqual({
      title: true, polish: false, image: true, executionMode: 'serial', maxConcurrent: 5,
    })

    preferenceApi.fetchPreference.mockImplementation((key: string) => Promise.resolve(
      key === 'note-ai-defaults-v1' ? JSON.stringify({ title: false, polish: true, image: true }) : '',
    ))
    preferences.prepareForMember('member-with-legacy-settings')
    await preferences.load()
    expect(preferences.noteAiDefaults).toEqual({
      title: false, polish: true, image: true, executionMode: 'parallel', maxConcurrent: 3,
    })
  })


  it('小记提醒偏好拒绝未知间隔并在读取失败值时回到安全默认', async () => {
    preferenceApi.fetchPreference.mockImplementation((key: string) => Promise.resolve(
      key === 'note-fullscreen-reminder-v1' ? JSON.stringify({ enabled: false, delayMinutes: 999 }) : '',
    ))
    const preferences = usePreferenceStore()
    await preferences.load()
    expect(preferences.noteFullscreenReminder).toEqual({ enabled: false, delayMinutes: 1 })
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

  it('从服务端读取周起始日与庆祝动画并原子保存', async () => {
    preferenceApi.fetchPreference.mockImplementation((key: string) => Promise.resolve(
      key === 'week-start' ? '周日' : key === 'celebration' ? '关闭' : '',
    ))
    const preferences = usePreferenceStore()
    await preferences.load()

    expect(preferences.weekStart).toBe('周日')
    expect(preferences.celebration).toBe('关闭')

    await preferences.setWeekStart('周一')
    await preferences.setCelebration('细腻纸屑')

    expect(preferenceApi.savePreference).toHaveBeenCalledWith('week-start', '周一')
    expect(preferenceApi.savePreference).toHaveBeenCalledWith('celebration', '细腻纸屑')
    expect(preferences.weekStart).toBe('周一')
    expect(preferences.celebration).toBe('细腻纸屑')
  })

  it('周起始日保存失败时保留服务端原值', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()
    preferenceApi.savePreference.mockRejectedValueOnce(new Error('网络不可用'))

    await expect(preferences.setWeekStart('周日')).rejects.toThrow('网络不可用')
    expect(preferences.weekStart).toBe('周一')
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
