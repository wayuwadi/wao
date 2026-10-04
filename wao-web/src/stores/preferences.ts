import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchPreference, savePreference } from '@/api/favorites'
import { isPageLayoutStyle, normalizePageLayoutStyle, type PageLayoutStyle } from '@/utils/pageLayout'

export type WeekStart = '周一' | '周日'
export type Celebration = '细腻纸屑' | '关闭'
export type ControlStyle = 'system' | 'minimal' | 'detailed'
const KEYS = { weekStart: 'week-start', celebration: 'celebration' } as const
const DEFAULT_PROJECT_KEY = 'default-project'
const NOTE_FULLSCREEN_REMINDER_KEY = 'note-fullscreen-reminder-v1'
const NOTE_AI_DEFAULTS_KEY = 'note-ai-defaults-v1'
const CONTROL_STYLE_KEY = 'control-style-v1'
const PAGE_LAYOUT_KEY = 'page-layout-style-v1'

export interface NoteFullscreenReminderSettings {
  enabled: boolean
  delayMinutes: 1 | 2 | 3 | 5 | 10 | 15
}

export interface NoteAiDefaults {
  title: boolean
  polish: boolean
  image: boolean
  executionMode: 'parallel' | 'serial'
  maxConcurrent: 1 | 2 | 3 | 4 | 5
}

const DEFAULT_NOTE_FULLSCREEN_REMINDER_SETTINGS: NoteFullscreenReminderSettings = {
  enabled: true,
  delayMinutes: 1,
}

const DEFAULT_NOTE_AI_DEFAULTS: NoteAiDefaults = {
  title: true, polish: true, image: true, executionMode: 'parallel', maxConcurrent: 3,
}

const NOTE_REMINDER_DELAYS: NoteFullscreenReminderSettings['delayMinutes'][] = [1, 2, 3, 5, 10, 15]

function parseNoteFullscreenReminderSettings(value: string): NoteFullscreenReminderSettings {
  try {
    const parsed = JSON.parse(value) as Partial<NoteFullscreenReminderSettings>
    return {
      enabled: typeof parsed.enabled === 'boolean' ? parsed.enabled : DEFAULT_NOTE_FULLSCREEN_REMINDER_SETTINGS.enabled,
      delayMinutes: NOTE_REMINDER_DELAYS.includes(parsed.delayMinutes as NoteFullscreenReminderSettings['delayMinutes'])
        ? parsed.delayMinutes as NoteFullscreenReminderSettings['delayMinutes']
        : DEFAULT_NOTE_FULLSCREEN_REMINDER_SETTINGS.delayMinutes,
    }
  } catch {
    return { ...DEFAULT_NOTE_FULLSCREEN_REMINDER_SETTINGS }
  }
}

function parseNoteAiDefaults(value: string): NoteAiDefaults {
  try {
    const parsed = JSON.parse(value) as Partial<NoteAiDefaults>
    return {
      title: typeof parsed.title === 'boolean' ? parsed.title : DEFAULT_NOTE_AI_DEFAULTS.title,
      polish: typeof parsed.polish === 'boolean' ? parsed.polish : DEFAULT_NOTE_AI_DEFAULTS.polish,
      image: typeof parsed.image === 'boolean' ? parsed.image : DEFAULT_NOTE_AI_DEFAULTS.image,
      executionMode: parsed.executionMode === 'serial' ? 'serial' : DEFAULT_NOTE_AI_DEFAULTS.executionMode,
      maxConcurrent: [1, 2, 3, 4, 5].includes(parsed.maxConcurrent as number)
        ? parsed.maxConcurrent as NoteAiDefaults['maxConcurrent']
        : DEFAULT_NOTE_AI_DEFAULTS.maxConcurrent,
    }
  } catch {
    return { ...DEFAULT_NOTE_AI_DEFAULTS }
  }
}

/** 登录成员的界面偏好：服务端存储，读取后立即作用到页面。 */
export const usePreferenceStore = defineStore('preferences', () => {
  const weekStart = ref<WeekStart>('周一')
  const celebration = ref<Celebration>('细腻纸屑')
  const defaultProject = ref('')
  const controlStyle = ref<ControlStyle>('system')
  const pageLayoutStyle = ref<PageLayoutStyle>('unified')
  const effectiveControlStyle = computed(() => controlStyle.value === 'detailed' ? 'detailed' : 'minimal')
  const compactControls = computed(() => effectiveControlStyle.value === 'minimal')
  const noteFullscreenReminder = ref<NoteFullscreenReminderSettings>({ ...DEFAULT_NOTE_FULLSCREEN_REMINDER_SETTINGS })
  const noteAiDefaults = ref<NoteAiDefaults>({ ...DEFAULT_NOTE_AI_DEFAULTS })
  const memberId = ref('')
  const loaded = ref(false)
  const loading = ref(false)
  const loadError = ref('')
  let loadVersion = 0
  let pageLayoutEditVersion = 0
  const valid = <T extends string>(value: string, choices: readonly T[], fallback: T): T => choices.includes(value as T) ? value as T : fallback
  function prepareForMember(nextMemberId: string) {
    if (memberId.value === nextMemberId) return
    loadVersion++
    pageLayoutEditVersion++
    memberId.value = nextMemberId
    weekStart.value = '周一'
    celebration.value = '细腻纸屑'
    defaultProject.value = ''
    controlStyle.value = 'system'
    pageLayoutStyle.value = 'unified'
    noteFullscreenReminder.value = { ...DEFAULT_NOTE_FULLSCREEN_REMINDER_SETTINGS }
    noteAiDefaults.value = { ...DEFAULT_NOTE_AI_DEFAULTS }
    loaded.value = false
    loading.value = false
    loadError.value = ''
  }
  async function load(force = false) {
    if ((loaded.value && !force) || loading.value) return
    const requestVersion = ++loadVersion
    const pageLayoutVersion = pageLayoutEditVersion
    loading.value = true
    loadError.value = ''
    try {
      const [savedWeekStart, savedCelebration, savedDefaultProject, savedControlStyle, savedNoteFullscreenReminder, savedNoteAiDefaults, savedPageLayout] = await Promise.all([
        fetchPreference(KEYS.weekStart),
        fetchPreference(KEYS.celebration),
        fetchPreference(DEFAULT_PROJECT_KEY),
        fetchPreference(CONTROL_STYLE_KEY),
        fetchPreference(NOTE_FULLSCREEN_REMINDER_KEY),
        fetchPreference(NOTE_AI_DEFAULTS_KEY),
        fetchPreference(PAGE_LAYOUT_KEY),
      ])
      if (requestVersion !== loadVersion) return
      weekStart.value = valid(savedWeekStart, ['周一', '周日'], '周一')
      celebration.value = valid(savedCelebration, ['细腻纸屑', '关闭'], '细腻纸屑')
      defaultProject.value = savedDefaultProject
      controlStyle.value = valid(savedControlStyle, ['system', 'minimal', 'detailed'], 'system')
      if (pageLayoutVersion === pageLayoutEditVersion) pageLayoutStyle.value = normalizePageLayoutStyle(savedPageLayout)
      noteFullscreenReminder.value = parseNoteFullscreenReminderSettings(savedNoteFullscreenReminder)
      noteAiDefaults.value = parseNoteAiDefaults(savedNoteAiDefaults)
    } catch (error) {
      if (requestVersion !== loadVersion) return
      loadError.value = error instanceof Error ? error.message : '个人偏好读取失败'
      // 读取失败保留错误，不把默认值冒充已保存偏好。
    } finally {
      if (requestVersion === loadVersion) {
        loaded.value = !loadError.value
        loading.value = false
      }
    }
  }
  async function setWeekStart(value: WeekStart) {
    await savePreference(KEYS.weekStart, value)
    weekStart.value = value
    loadError.value = ''
  }
  async function setCelebration(value: Celebration) {
    await savePreference(KEYS.celebration, value)
    celebration.value = value
    loadError.value = ''
  }
  async function setDefaultProject(value: string) { defaultProject.value = value; await savePreference(DEFAULT_PROJECT_KEY, value) }
  async function setControlStyle(value: ControlStyle) {
    await savePreference(CONTROL_STYLE_KEY, value)
    controlStyle.value = value
    loadError.value = ''
  }
  async function setPageLayoutStyle(value: PageLayoutStyle) {
    if (!isPageLayoutStyle(value)) throw new Error('未知页面布局')
    const owner = memberId.value
    const editVersion = ++pageLayoutEditVersion
    await savePreference(PAGE_LAYOUT_KEY, value)
    if (owner !== memberId.value || editVersion !== pageLayoutEditVersion) return
    pageLayoutStyle.value = value
  }
  async function setNoteFullscreenReminder(settings: NoteFullscreenReminderSettings) {
    const normalized: NoteFullscreenReminderSettings = {
      enabled: settings.enabled,
      delayMinutes: NOTE_REMINDER_DELAYS.includes(settings.delayMinutes) ? settings.delayMinutes : 1,
    }
    await savePreference(NOTE_FULLSCREEN_REMINDER_KEY, JSON.stringify(normalized))
    noteFullscreenReminder.value = normalized
    loadError.value = ''
  }
  async function setNoteAiDefaults(settings: NoteAiDefaults) {
    const normalized: NoteAiDefaults = {
      title: settings.title,
      polish: settings.polish,
      image: settings.image,
      executionMode: settings.executionMode === 'serial' ? 'serial' : 'parallel',
      maxConcurrent: [1, 2, 3, 4, 5].includes(settings.maxConcurrent) ? settings.maxConcurrent : 3,
    }
    await savePreference(NOTE_AI_DEFAULTS_KEY, JSON.stringify(normalized))
    noteAiDefaults.value = normalized
    loadError.value = ''
  }
  return {
    weekStart, celebration, defaultProject, controlStyle, effectiveControlStyle, compactControls, pageLayoutStyle,
    noteFullscreenReminder, noteAiDefaults,
    loaded, loading, loadError,
    prepareForMember, load, setWeekStart, setCelebration, setDefaultProject, setControlStyle, setPageLayoutStyle, setNoteFullscreenReminder, setNoteAiDefaults,
  }
})
