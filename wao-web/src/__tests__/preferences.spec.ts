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

  it('默认项目保存失败不冒充成功，重试后生效', async () => {
    const prefs = usePreferenceStore(); prefs.defaultProject = 'old'
    preferenceApi.savePreference.mockRejectedValueOnce(new Error('offline'))
    await expect(prefs.setDefaultProject('new')).rejects.toThrow('offline')
    expect(prefs.defaultProject).toBe('old')
    await prefs.setDefaultProject('new'); expect(prefs.defaultProject).toBe('new')
  })
  it('迟到默认项目读取与上一成员保存不能回退较新选择', async () => {
    const prefs = usePreferenceStore(); prefs.prepareForMember('a')
    let finish!: (v: string) => void
    preferenceApi.fetchPreference.mockImplementation(key => key === 'default-project' ? new Promise<string>(r => { finish = r }) : Promise.resolve(''))
    const read = prefs.load(); await prefs.setDefaultProject('new')
    finish('old'); await read; expect(prefs.defaultProject).toBe('new')
    let saved!: () => void
    preferenceApi.savePreference.mockImplementationOnce(() => new Promise<void>(r => { saved = r }))
    const write = prefs.setDefaultProject('a-project'); prefs.prepareForMember('b')
    await prefs.setDefaultProject('b-project'); saved(); await write
    expect(prefs.defaultProject).toBe('b-project')
  })

  it('页面布局默认统一外框，未知旧值安全回退，三种布局可持久化读取', async () => {
    const preferences = usePreferenceStore()
    expect(preferences.pageLayoutStyle).toBe('unified')
    for (const style of ['unified', 'fluid', 'centered', 'unknown']) {
      preferenceApi.fetchPreference.mockImplementation(key => Promise.resolve(key === 'page-layout-style-v1' ? style : ''))
      await preferences.load(true)
      expect(preferences.pageLayoutStyle).toBe(style === 'unknown' ? 'unified' : style)
    }
    await preferences.setPageLayoutStyle('fluid')
    expect(preferenceApi.savePreference).toHaveBeenLastCalledWith('page-layout-style-v1', 'fluid')
    expect(preferences.pageLayoutStyle).toBe('fluid')
  })

  it('页面布局保存失败保持已保存值，重试成功后才应用', async () => {
    const preferences = usePreferenceStore()
    preferenceApi.savePreference.mockRejectedValueOnce(new Error('网络不可用'))
    await expect(preferences.setPageLayoutStyle('centered')).rejects.toThrow('网络不可用')
    expect(preferences.pageLayoutStyle).toBe('unified')
    await preferences.setPageLayoutStyle('centered')
    expect(preferences.pageLayoutStyle).toBe('centered')
  })

  it('上一名成员的迟到布局保存不能覆盖新成员', async () => {
    const preferences = usePreferenceStore()
    preferences.prepareForMember('a')
    let finish!: () => void
    preferenceApi.savePreference.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
    const firstSave = preferences.setPageLayoutStyle('centered')
    preferences.prepareForMember('b')
    await preferences.setPageLayoutStyle('fluid')
    finish()
    await firstSave
    expect(preferences.pageLayoutStyle).toBe('fluid')
  })

  it('读取中的旧布局不能回退刚保存的选择', async () => {
    const preferences = usePreferenceStore()
    let finish!: (value: string) => void
    preferenceApi.fetchPreference.mockImplementation(key => key === 'page-layout-style-v1'
      ? new Promise<string>(resolve => { finish = resolve }) : Promise.resolve(''))
    const read = preferences.load()
    await preferences.setPageLayoutStyle('fluid')
    finish('centered')
    await read
    expect(preferences.pageLayoutStyle).toBe('fluid')
  })

  it('切换成员先恢复统一外框，上一人的迟到读取不能覆盖新成员', async () => {
    const preferences = usePreferenceStore()
    let finish!: (value: string) => void
    preferenceApi.fetchPreference.mockImplementation(key => key === 'page-layout-style-v1'
      ? new Promise<string>(resolve => { finish = resolve }) : Promise.resolve(''))
    preferences.prepareForMember('a')
    const read = preferences.load()
    preferences.prepareForMember('b')
    expect(preferences.pageLayoutStyle).toBe('unified')
    preferenceApi.fetchPreference.mockResolvedValue('fluid')
    await preferences.load()
    finish('centered')
    await read
    expect(preferences.pageLayoutStyle).toBe('fluid')
  })

  it('未配置时使用默认控件风格且不再读取已下线的音乐偏好', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()

    expect(preferences.controlStyle).toBe('system')
    expect(preferences.effectiveControlStyle).toBe('minimal')
    expect(preferences.compactControls).toBe(true)
    expect(preferenceApi.fetchPreference).not.toHaveBeenCalledWith('music-player-settings-v1')
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

  it('切换登录成员时先清除上一人的界面偏好', async () => {
    preferenceApi.fetchPreference.mockImplementation((key: string) => Promise.resolve(
      key === 'control-style-v1' ? 'detailed' : '',
    ))
    const preferences = usePreferenceStore()
    preferences.prepareForMember('member-a')
    await preferences.load()
    expect(preferences.controlStyle).toBe('detailed')

    preferences.prepareForMember('member-b')
    expect(preferences.loaded).toBe(false)
    expect(preferences.controlStyle).toBe('system')
  })

  it('上一名成员的迟到响应不会覆盖新成员偏好', async () => {
    let resolveFirstStyle!: (value: string) => void
    let servingFirstMember = true
    preferenceApi.fetchPreference.mockImplementation((key: string) => {
      if (key !== 'control-style-v1') return Promise.resolve('')
      if (servingFirstMember) return new Promise<string>((resolve) => { resolveFirstStyle = resolve })
      return Promise.resolve('minimal')
    })
    const preferences = usePreferenceStore()
    preferences.prepareForMember('member-a')
    const firstLoad = preferences.load()

    servingFirstMember = false
    preferences.prepareForMember('member-b')
    await preferences.load()
    resolveFirstStyle('detailed')
    await firstLoad

    expect(preferences.controlStyle).toBe('minimal')
  })
})
