import { ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchPreference, savePreference } from '@/api/favorites'

export type WeekStart = '周一' | '周日'
export type Celebration = '细腻纸屑' | '关闭'
const KEYS = { weekStart: 'week-start', celebration: 'celebration' } as const
const DEFAULT_PROJECT_KEY = 'default-project'

/** 登录成员的界面偏好：服务端存储，读取后立即作用到页面。 */
export const usePreferenceStore = defineStore('preferences', () => {
  const weekStart = ref<WeekStart>('周一')
  const celebration = ref<Celebration>('细腻纸屑')
  const defaultProject = ref('')
  const loaded = ref(false)
  const valid = <T extends string>(value: string, choices: readonly T[], fallback: T): T => choices.includes(value as T) ? value as T : fallback
  async function load() {
    if (loaded.value) return
    try {
      const [savedWeekStart, savedCelebration, savedDefaultProject] = await Promise.all([
        fetchPreference(KEYS.weekStart),
        fetchPreference(KEYS.celebration),
        fetchPreference(DEFAULT_PROJECT_KEY),
      ])
      weekStart.value = valid(savedWeekStart, ['周一', '周日'], '周一')
      celebration.value = valid(savedCelebration, ['细腻纸屑', '关闭'], '细腻纸屑')
      defaultProject.value = savedDefaultProject
    } catch {
      // 离线或会话尚未就绪时继续使用默认值，不让应用启动失败。
    } finally { loaded.value = true }
  }
  async function setWeekStart(value: WeekStart) { weekStart.value = value; await savePreference(KEYS.weekStart, value) }
  async function setCelebration(value: Celebration) { celebration.value = value; await savePreference(KEYS.celebration, value) }
  async function setDefaultProject(value: string) { defaultProject.value = value; await savePreference(DEFAULT_PROJECT_KEY, value) }
  return {
    weekStart, celebration, defaultProject, loaded,
    load, setWeekStart, setCelebration, setDefaultProject,
  }
})
