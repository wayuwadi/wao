import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchPreference, savePreference } from '@/api/favorites'

export type WeekStart = '周一' | '周日'
export type Celebration = '细腻纸屑' | '关闭'
export type ControlStyle = 'system' | 'minimal' | 'detailed'
const KEYS = { weekStart: 'week-start', celebration: 'celebration' } as const
const DEFAULT_PROJECT_KEY = 'default-project'
const MUSIC_PLAYER_SETTINGS_KEY = 'music-player-settings-v1'
const CONTROL_STYLE_KEY = 'control-style-v1'

export interface MusicPlayerSettings {
  defaultOpen: boolean
  launcherVisible: boolean
}

const DEFAULT_MUSIC_PLAYER_SETTINGS: MusicPlayerSettings = {
  defaultOpen: false,
  launcherVisible: true,
}

function parseMusicPlayerSettings(value: string): MusicPlayerSettings {
  try {
    const parsed = JSON.parse(value) as Partial<MusicPlayerSettings>
    return {
      defaultOpen: typeof parsed.defaultOpen === 'boolean' ? parsed.defaultOpen : false,
      launcherVisible: typeof parsed.launcherVisible === 'boolean' ? parsed.launcherVisible : true,
    }
  } catch {
    return { ...DEFAULT_MUSIC_PLAYER_SETTINGS }
  }
}

/** 登录成员的界面偏好：服务端存储，读取后立即作用到页面。 */
export const usePreferenceStore = defineStore('preferences', () => {
  const weekStart = ref<WeekStart>('周一')
  const celebration = ref<Celebration>('细腻纸屑')
  const defaultProject = ref('')
  const controlStyle = ref<ControlStyle>('system')
  const effectiveControlStyle = computed(() => controlStyle.value === 'detailed' ? 'detailed' : 'minimal')
  const compactControls = computed(() => effectiveControlStyle.value === 'minimal')
  const musicPlayerDefaultOpen = ref(DEFAULT_MUSIC_PLAYER_SETTINGS.defaultOpen)
  const musicPlayerLauncherVisible = ref(DEFAULT_MUSIC_PLAYER_SETTINGS.launcherVisible)
  const memberId = ref('')
  const loaded = ref(false)
  const loading = ref(false)
  const loadError = ref('')
  let loadVersion = 0
  const valid = <T extends string>(value: string, choices: readonly T[], fallback: T): T => choices.includes(value as T) ? value as T : fallback
  function prepareForMember(nextMemberId: string) {
    if (memberId.value === nextMemberId) return
    loadVersion++
    memberId.value = nextMemberId
    weekStart.value = '周一'
    celebration.value = '细腻纸屑'
    defaultProject.value = ''
    controlStyle.value = 'system'
    musicPlayerDefaultOpen.value = DEFAULT_MUSIC_PLAYER_SETTINGS.defaultOpen
    musicPlayerLauncherVisible.value = DEFAULT_MUSIC_PLAYER_SETTINGS.launcherVisible
    loaded.value = false
    loading.value = false
    loadError.value = ''
  }
  async function load(force = false) {
    if ((loaded.value && !force) || loading.value) return
    const requestVersion = ++loadVersion
    loading.value = true
    loadError.value = ''
    try {
      const [savedWeekStart, savedCelebration, savedDefaultProject, savedMusicPlayerSettings, savedControlStyle] = await Promise.all([
        fetchPreference(KEYS.weekStart),
        fetchPreference(KEYS.celebration),
        fetchPreference(DEFAULT_PROJECT_KEY),
        fetchPreference(MUSIC_PLAYER_SETTINGS_KEY),
        fetchPreference(CONTROL_STYLE_KEY),
      ])
      if (requestVersion !== loadVersion) return
      weekStart.value = valid(savedWeekStart, ['周一', '周日'], '周一')
      celebration.value = valid(savedCelebration, ['细腻纸屑', '关闭'], '细腻纸屑')
      defaultProject.value = savedDefaultProject
      controlStyle.value = valid(savedControlStyle, ['system', 'minimal', 'detailed'], 'system')
      const musicPlayerSettings = parseMusicPlayerSettings(savedMusicPlayerSettings)
      musicPlayerDefaultOpen.value = musicPlayerSettings.defaultOpen
      musicPlayerLauncherVisible.value = musicPlayerSettings.launcherVisible
    } catch (error) {
      if (requestVersion !== loadVersion) return
      loadError.value = error instanceof Error ? error.message : '个人偏好读取失败'
      // 离线或会话尚未就绪时继续使用默认值，不让应用启动失败。
    } finally {
      if (requestVersion === loadVersion) {
        loaded.value = true
        loading.value = false
      }
    }
  }
  async function setWeekStart(value: WeekStart) { weekStart.value = value; await savePreference(KEYS.weekStart, value) }
  async function setCelebration(value: Celebration) { celebration.value = value; await savePreference(KEYS.celebration, value) }
  async function setDefaultProject(value: string) { defaultProject.value = value; await savePreference(DEFAULT_PROJECT_KEY, value) }
  async function setControlStyle(value: ControlStyle) {
    await savePreference(CONTROL_STYLE_KEY, value)
    controlStyle.value = value
    loadError.value = ''
  }
  async function setMusicPlayerSettings(settings: MusicPlayerSettings) {
    await savePreference(MUSIC_PLAYER_SETTINGS_KEY, JSON.stringify(settings))
    musicPlayerDefaultOpen.value = settings.defaultOpen
    musicPlayerLauncherVisible.value = settings.launcherVisible
    loadError.value = ''
  }
  return {
    weekStart, celebration, defaultProject, controlStyle, effectiveControlStyle, compactControls,
    musicPlayerDefaultOpen, musicPlayerLauncherVisible,
    loaded, loading, loadError,
    prepareForMember, load, setWeekStart, setCelebration, setDefaultProject, setControlStyle, setMusicPlayerSettings,
  }
})
